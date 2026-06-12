package com.icusu.sivan.infra.forest.execution;

import com.icusu.sivan.common.NodeStatus;
import com.icusu.sivan.domain.forest.Forest;
import com.icusu.sivan.domain.forest.context.ExecutionContext;
import com.icusu.sivan.domain.forest.service.ForestRepository;
import com.icusu.sivan.domain.forest.tree.ExecutableNode;
import com.icusu.sivan.domain.forest.tree.TreeNode;
import com.icusu.sivan.infra.forest.entity.ForestEntity;
import com.icusu.sivan.infra.forest.repository.ForestJpaRepository;
import com.icusu.sivan.infra.forest.repository.ForestNodeJpaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 启动恢复管理器 — 扫描所有 RUNNING 状态的 GoalTree 并恢复执行（09-持久化与恢复 §3.1）。
 * <p>
 * 应用启动时自动触发，流程：
 * <ol>
 *   <li>扫描所有账号下的 RUNNING 根节点</li>
 *   <li>对每棵树，找到最后一个 COMPLETED 节点</li>
 *   <li>从下一个 PENDING 节点恢复执行</li>
 *   <li>所有节点都已完成 → 标记树为 COMPLETED</li>
 * </ol>
 */
@Component
public class RecoveryManager {

    private static final Logger log = LoggerFactory.getLogger(RecoveryManager.class);

    private final ForestNodeJpaRepository forestNodeJpaRepository;
    private final ForestJpaRepository forestJpaRepository;
    private final ForestRepository forestRepository;
    private final GoalExecutionService goalExecutionService;

    public RecoveryManager(ForestNodeJpaRepository forestNodeJpaRepository,
                           ForestJpaRepository forestJpaRepository,
                           ForestRepository forestRepository,
                           GoalExecutionService goalExecutionService) {
        this.forestNodeJpaRepository = forestNodeJpaRepository;
        this.forestJpaRepository = forestJpaRepository;
        this.forestRepository = forestRepository;
        this.goalExecutionService = goalExecutionService;
    }

    /** 应用启动时自动执行恢复。 */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverOnStartup() {
        log.info("RecoveryManager: 启动恢复扫描...");
        var rootNodes = forestNodeJpaRepository.findAllRootNodesByStatus(NodeStatus.RUNNING.name());
        if (rootNodes.isEmpty()) {
            log.info("RecoveryManager: 无需要恢复的 GoalTree");
            return;
        }

        log.info("RecoveryManager: 发现 {} 个待恢复的 GoalTree", rootNodes.size());
        AtomicInteger success = new AtomicInteger(0);

        for (var rootEntity : rootNodes) {
            String rootNodeId = rootEntity.getNodeId();
            UUID forestId = rootEntity.getForestId();
            try {
                resumeTree(rootNodeId, forestId);
                success.incrementAndGet();
            } catch (Exception e) {
                log.error("RecoveryManager: GoalTree 恢复失败 nodeId={}: {}", rootNodeId, e.getMessage());
                forestRepository.updateNodeStatus(rootNodeId, NodeStatus.FAILED, null);
            }
        }

        log.info("RecoveryManager: 恢复完成 {}/{}", success.get(), rootNodes.size());
    }

    /** 从断点恢复一棵树。 */
    private void resumeTree(String rootNodeId, UUID forestId) {
        ForestEntity forestEntity = forestJpaRepository.findById(forestId).orElse(null);
        if (forestEntity == null) {
            log.warn("RecoveryManager: Forest 不存在 forestId={}", forestId);
            return;
        }
        UUID accountId = forestEntity.getAccountId();

        // 加载完整子树
        TreeNode root = forestRepository.findSubtree(rootNodeId, accountId);
        if (root == null) {
            log.warn("RecoveryManager: 子树加载失败 rootNodeId={}", rootNodeId);
            return;
        }
        if (!(root instanceof ExecutableNode execRoot)) {
            log.warn("RecoveryManager: 根节点不可执行 rootNodeId={}", rootNodeId);
            return;
        }

        // 找最后一个 COMPLETED 叶子 → 下一个 PENDING
        TreeNode lastCompleted = findLastCompleted(execRoot);
        ExecutableNode nextPending;

        if (lastCompleted != null) {
            var next = forestRepository.findNextSibling(
                    lastCompleted.nodeId(), forestId, accountId);
            nextPending = next instanceof ExecutableNode en ? en : null;
        } else {
            nextPending = execRoot;
        }

        if (nextPending == null) {
            execRoot.setStatus(NodeStatus.COMPLETED);
            log.info("RecoveryManager: GoalTree 全部完成 rootNodeId={}", rootNodeId);
            return;
        }

        log.info("RecoveryManager: 恢复执行 rootNodeId={} from={}", rootNodeId, nextPending.nodeId());

        // 构建 Forest 对象并从断点继续执行
        Forest forest = new Forest(forestId, accountId, forestEntity.getProjectId(),
                forestEntity.getConversationId(),
                forestEntity.getTitle(), rootNodeId);
        ExecutionContext ctx = ExecutionContext.create(accountId);

        // 使用 executeOnly 重新执行（不会重复创建森林结构）
        goalExecutionService.executeOnly(forest, execRoot, ctx,
                com.icusu.sivan.domain.forest.context.Delivery.STREAM)
                .subscribe(
                        event -> {},
                        error -> log.error("RecoveryManager: 恢复执行异常: {}", error.getMessage()),
                        () -> log.info("RecoveryManager: 恢复执行完成 rootNodeId={}", rootNodeId)
                );
    }

    /** 深度遍历找最后一个 COMPLETED 叶子。 */
    private TreeNode findLastCompleted(TreeNode node) {
        if (node.children().isEmpty()) {
            if (node instanceof ExecutableNode en && en.status() == NodeStatus.COMPLETED) {
                return node;
            }
            return null;
        }
        TreeNode last = null;
        for (TreeNode child : node.children()) {
            TreeNode found = findLastCompleted(child);
            if (found != null) last = found;
        }
        return last;
    }
}
