package com.icusu.sivan.infra.forest.execution;

import com.icusu.sivan.domain.forest.Forest;
import com.icusu.sivan.domain.forest.ForestEvent;
import com.icusu.sivan.domain.forest.context.Delivery;
import com.icusu.sivan.domain.forest.context.ExecutionContext;
import com.icusu.sivan.domain.forest.service.ExecutionCommand;
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
 * 引擎执行通过 {@link ForestScheduler} 调度，支持并发控制和排队。
 */
@Component
public class GoalExecutionService {
    private static final Logger log = LoggerFactory.getLogger(GoalExecutionService.class);

    private final ForestScheduler forestScheduler;
    private final ForestRepository forestRepo;
    private final TransactionTemplate transactionTemplate;
    private final Map<UUID, ExecutionContext> activeExecutions = new ConcurrentHashMap<>();

    public GoalExecutionService(ForestScheduler forestScheduler, ForestRepository forestRepo,
                                TransactionTemplate transactionTemplate) {
        this.forestScheduler = forestScheduler;
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
     * 通过 {@link ForestScheduler} 提交执行，支持并发控制和排队。
     * 调度器空闲时立即执行，繁忙时入队等待。
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

        // 独立事务持久化森林结构
        persistForestStructure(forest, root, ctx);

        activeExecutions.put(forestId, ctx);

        // 通过调度器提交，支持并发控制和排队
        ExecutionCommand cmd = new ExecutionCommand(root, ctx, delivery, forestId.toString());
        return forestScheduler.submit(cmd)
                .doFinally(signal -> {
                    activeExecutions.remove(forestId);
                    log.info("[执行] 注销 forest: forestId={} signal={}", forestId, signal);
                });
    }

    /**
     * 在独立事务中保存森林聚合根和节点树。
     */
    private void persistForestStructure(Forest forest, ExecutableNode root, ExecutionContext ctx) {
        transactionTemplate.executeWithoutResult(status -> {
            forestRepo.saveForest(forest, ctx.accountId());
            forestRepo.saveTree(root, forest.forestId(), ctx.accountId());
        });
    }

    /**
     * 仅调度执行（不持久化森林结构，适用于 ForestConversationService 等调用方已持久化的场景）。
     *
     * @param forest   Forest 聚合根（用于获取 forestId）
     * @param root     可执行的根节点
     * @param ctx      执行上下文
     * @param delivery 传递模式
     * @return 执行事件流
     */
    public Flux<ForestEvent> executeOnly(Forest forest, ExecutableNode root, ExecutionContext ctx, Delivery delivery) {
        UUID forestId = forest.forestId();
        activeExecutions.put(forestId, ctx);
        ExecutionCommand cmd = new ExecutionCommand(root, ctx, delivery, forestId.toString());
        return forestScheduler.submit(cmd)
                .doFinally(signal -> {
                    activeExecutions.remove(forestId);
                });
    }

    /**
     * 取消正在执行的目标树。
     *
     * @param forestId 目标树 ID
     * @return true 如果找到并取消了执行
     */
    public boolean cancelExecution(UUID forestId) {
        ExecutionContext ctx = activeExecutions.get(forestId);
        if (ctx != null) {
            ctx.cancel();
            log.info("[取消] 已发送取消信号: forestId={}", forestId);
            return true;
        }
        log.warn("[取消] 未找到活跃执行: forestId={}", forestId);
        return false;
    }

    /** 当前活跃执行数。 */
    public int activeCount() { return forestScheduler.activeCount(); }

    /** 当前排队数。 */
    public int queuedCount() { return forestScheduler.queuedCount(); }
}
