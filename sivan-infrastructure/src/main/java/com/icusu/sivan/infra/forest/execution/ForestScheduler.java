package com.icusu.sivan.infra.forest.execution;

import com.icusu.sivan.domain.forest.ForestEvent;
import com.icusu.sivan.domain.forest.context.Delivery;
import com.icusu.sivan.domain.forest.context.ExecutionContext;
import com.icusu.sivan.domain.forest.service.ExecutionCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import jakarta.annotation.PreDestroy;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Forest 调度器 — Command + Scheduler 模式的多树执行控制。
 * <p>
 * 职责：
 * <ul>
 *   <li>限制并发执行数（信号量）</li>
 *   <li>超额请求入队，按优先级 + 提交时间调度</li>
 *   <li>支持 FIFO / 优先级队列策略</li>
 *   <li>跟踪活跃执行，支持取消</li>
 * </ul>
 * <p>
 * 三种排队策略：
 * <ul>
 *   <li>{@link ConcurrentLinkedQueue} — FIFO，行为可预测</li>
 *   <li>{@link PriorityBlockingQueue} — 优先级调度</li>
 *   <li>自定义依赖感知队列（预留扩展）</li>
 * </ul>
 */
@Component
public class ForestScheduler {

    private static final Logger log = LoggerFactory.getLogger(ForestScheduler.class);

    /** 最大并发执行数 */
    private final int maxConcurrent;

    /** 并发控制信号量 */
    private final Semaphore slot;

    /** 排队队列 */
    private final Queue<ExecutionCommand> queue;

    /** 活跃执行 tracking */
    private final ConcurrentHashMap<String, ExecutionContext> activeExecutions = new ConcurrentHashMap<>();

    private final ForestExecutor forestExecutor;

    /** 排队等待数 */
    private final AtomicInteger queuedCount = new AtomicInteger(0);

    public ForestScheduler(
            ForestExecutor forestExecutor,
            @Value("${sivan.orchestration.scheduler.max-concurrent:3}") int maxConcurrent,
            @Value("${sivan.orchestration.scheduler.queue-strategy:fifo}") String queueStrategy
    ) {
        this.forestExecutor = forestExecutor;
        this.maxConcurrent = maxConcurrent;
        this.slot = new Semaphore(maxConcurrent);
        this.queue = createQueue(queueStrategy);
        log.info("[Scheduler] 初始化: maxConcurrent={} queueStrategy={}", maxConcurrent, queueStrategy);
    }

    // ===== 公开 API =====

    /**
     * 提交执行命令。有空闲槽位则立即执行，否则入队等待。
     *
     * @param cmd 执行命令
     * @return 完成信号（仅在排队时有效：出队后开始真正执行）
     */
    public Flux<ForestEvent> submit(ExecutionCommand cmd) {
        ExecutionContext ctx = cmd.ctx();
        String key = cmd.commandId().toString().substring(0, 8);

        if (slot.tryAcquire()) {
            log.info("[Scheduler] 立即执行: cmdId={}", key);
            return run(cmd);
        }

        queue.offer(cmd);
        queuedCount.incrementAndGet();
        log.info("[Scheduler] 入队等待: cmdId={} 队列长度={}", key, queue.size());
        return Flux.defer(() -> {
            // 等待出队后执行（通过 runNext 触发）
            return waitAndRun(cmd)
                    .doFinally(s -> queuedCount.decrementAndGet());
        });
    }

    /**
     * 取消指定的执行命令。
     *
     * @param commandId 命令 ID
     * @return true 如果找到并取消了执行
     */
    public boolean cancel(String commandId) {
        ExecutionContext ctx = activeExecutions.get(commandId);
        if (ctx != null) {
            ctx.cancel();
            log.info("[Scheduler] 已取消: cmdId={}", commandId.substring(0, 8));
            return true;
        }
        // 尝试从队列移除
        boolean removed = queue.removeIf(cmd -> cmd.commandId().toString().startsWith(commandId));
        if (removed) log.info("[Scheduler] 已从队列移除: cmdId={}", commandId.substring(0, 8));
        return removed;
    }

    /** 当前活跃执行数。 */
    public int activeCount() {
        return maxConcurrent - slot.availablePermits();
    }

    /** 当前排队数。 */
    public int queuedCount() {
        return queuedCount.get();
    }

    /** 总执行约束（活跃 + 排队）。 */
    public int totalPending() { return activeCount() + queuedCount(); }

    // ===== 内部方法 =====

    private Flux<ForestEvent> run(ExecutionCommand cmd) {
        String key = cmd.commandId().toString().substring(0, 8);
        activeExecutions.put(key, cmd.ctx());

        return forestExecutor.execute(cmd.root(), cmd.ctx(), cmd.delivery(), cmd.forestId())
                .doFinally(signal -> {
                    activeExecutions.remove(key);
                    slot.release();
                    log.info("[Scheduler] 执行完成: cmdId={} signal={} 队列剩余={}",
                            key, signal, queue.size());
                    runNext();
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private void runNext() {
        ExecutionCommand next = queue.poll();
        if (next != null && slot.tryAcquire()) {
            log.info("[Scheduler] 出队执行: cmdId={}", next.commandId().toString().substring(0, 8));
            run(next).subscribe(
                    null,
                    error -> log.error("[Scheduler] 队列执行异常: {}", error.getMessage())
            );
        }
    }

    private Flux<ForestEvent> waitAndRun(ExecutionCommand cmd) {
        return Flux.<ForestEvent>create(sink -> {
            // 轮询等待出队（由 runNext 触发 slot 释放）
            // 实际执行由 runNext 驱动，此处仅返回一个空 Flux
            sink.complete();
        }).thenMany(run(cmd));
    }

    private static Queue<ExecutionCommand> createQueue(String strategy) {
        return switch (strategy.toLowerCase()) {
            case "priority" -> new PriorityBlockingQueue<>();
            default -> new ConcurrentLinkedQueue<>();
        };
    }

    @PreDestroy
    void shutdown() {
        int active = activeCount();
        int queued = queuedCount();
        if (active > 0 || queued > 0) {
            log.warn("[Scheduler] 关闭时仍有 {} 活跃 + {} 排队", active, queued);
        }
    }
}
