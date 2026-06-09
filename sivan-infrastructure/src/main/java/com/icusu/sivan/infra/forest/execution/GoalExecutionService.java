package com.icusu.sivan.infra.forest.execution;

import com.icusu.sivan.domain.forest.Forest;
import com.icusu.sivan.domain.forest.ForestEvent;
import com.icusu.sivan.domain.forest.context.Delivery;
import com.icusu.sivan.domain.forest.context.ExecutionContext;
import com.icusu.sivan.domain.forest.service.ForestRepository;
import com.icusu.sivan.domain.forest.tree.ExecutableNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 目标执行服务 — 森林结构的持久化与执行入口。
 * <p>
 * 森林结构（Forest 聚合根 + 节点树）在独立事务中持久化，确保即使后续执行失败也不丢失记录。
 * 引擎执行在事务之外运行，节点状态通过 {@link NodeStatusPersistenceListener} 单独更新。
 */
@Component
public class GoalExecutionService {
    private static final Logger log = LoggerFactory.getLogger(GoalExecutionService.class);

    private final ForestExecutor forestExecutor;
    private final ForestRepository forestRepo;
    private final TransactionTemplate transactionTemplate;
    private final Map<UUID, ExecutionContext> activeExecutions = new ConcurrentHashMap<>();

    public GoalExecutionService(ForestExecutor forestExecutor, ForestRepository forestRepo,
                                TransactionTemplate transactionTemplate) {
        this.forestExecutor = forestExecutor;
        this.forestRepo = forestRepo;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 持久化 Forest 和树后执行（默认 STREAM 模式）。
     *
     * @param forest Forest 聚合根（含 forestId、accountId、title、rootNodeId）
     * @param root   可执行的根节点
     * @param ctx    执行上下文
     * @return 执行事件流
     */
    public Flux<ForestEvent> execute(Forest forest, ExecutableNode root, ExecutionContext ctx) {
        return execute(forest, root, ctx, Delivery.STREAM);
    }

    /**
     * 按指定传递模式持久化 Forest 和树后执行。
     * <p>
     * STREAM 模式：事件经过 MetricsSink → ErrorLogSink → SseSink，同时返回 Flux 给 SSE 响应。<br>
     * SUMMARY 模式：事件经过 MetricsSink → ErrorLogSink → NoopSink，不推 SSE。
     *
     * @param forest   Forest 聚合根
     * @param root     可执行的根节点
     * @param ctx      执行上下文
     * @param delivery 传递模式
     * @return 执行事件流
     */
    public Flux<ForestEvent> execute(Forest forest, ExecutableNode root, ExecutionContext ctx, Delivery delivery) {
        UUID forestId = forest.forestId();
        log.info("[执行] 持久化 forest: forestId={} root={} delivery={}", forestId, root.nodeId(), delivery);

        // 独立事务持久化森林结构，执行失败时状态保留在 DB 中，用于恢复和排查
        persistForestStructure(forest, root, ctx);

        activeExecutions.put(forestId, ctx);

        return forestExecutor.execute(root, ctx, delivery)
                .doFinally(signal -> {
                    activeExecutions.remove(forestId);
                    log.info("[执行] 注销 forest: forestId={} signal={}", forestId, signal);
                });
    }

    /**
     * 在独立事务中保存森林聚合根和节点树，确保即使后续执行失败也不丢失记录。
     */
    private void persistForestStructure(Forest forest, ExecutableNode root, ExecutionContext ctx) {
        transactionTemplate.executeWithoutResult(status -> {
            forestRepo.saveForest(forest, ctx.accountId());
            forestRepo.saveTree(root, forest.forestId(), ctx.accountId());
        });
    }

    /**
     * 取消正在执行的目标树。
     *
     * @param forestId 目标树 ID
     * @return true 如果找到并取消了执行，false 如果执行已结束或不存在
     */
    public boolean cancelExecution(UUID forestId) {
        ExecutionContext ctx = activeExecutions.get(forestId);
        if (ctx == null) {
            log.warn("[取消] 未找到活跃执行: forestId={}", forestId);
            return false;
        }
        ctx.cancel();
        log.info("[取消] 已发送取消信号: forestId={}", forestId);
        return true;
    }
}
