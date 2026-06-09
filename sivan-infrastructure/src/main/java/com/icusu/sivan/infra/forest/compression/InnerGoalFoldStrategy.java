package com.icusu.sivan.infra.forest.compression;

import com.icusu.sivan.common.NodeStatus;
import com.icusu.sivan.domain.compression.FoldDecision;
import com.icusu.sivan.domain.compression.FoldStrategy;
import com.icusu.sivan.domain.compression.TokenBudget;
import com.icusu.sivan.domain.forest.tree.InnerGoalNode;
import com.icusu.sivan.domain.forest.tree.TreeNode;

/**
 * InnerGoalNode 折叠策略。
 * <p>
 * COMPLETED → 折叠为 {@code "✅ 目标名(完成/总数)"}；
 * RUNNING → 保留，递归压缩子节点；
 * PENDING/FAILED → 检查 token 预算。
 */
public class InnerGoalFoldStrategy implements FoldStrategy {

    @Override
    public String supportedType() {
        return "inner_goal";
    }

    @Override
    public FoldDecision decide(TreeNode node, TokenBudget budget) {
        if (!(node instanceof InnerGoalNode goal)) {
            return FoldDecision.skip("不是 InnerGoalNode");
        }

        NodeStatus status = goal.status();
        long totalChildren = goal.children().size();
        long completedChildren = goal.children().stream()
                .filter(c -> c instanceof com.icusu.sivan.domain.forest.tree.ExecutableNode e
                        && e.status() == NodeStatus.COMPLETED)
                .count();

        return switch (status) {
            case COMPLETED -> FoldDecision.fold(
                    "✅ " + node.nodeType() + "(" + completedChildren + "/" + totalChildren + ")");
            case FAILED -> FoldDecision.fold(
                    "❌ " + node.nodeType() + "(" + completedChildren + "/" + totalChildren + ")");
            case RUNNING -> FoldDecision.skip("运行中，保留子节点");
            default -> {
                // PENDING / CANCELLED: 按预算决策
                int goalBudget = budget.forType("inner_goal");
                if (totalChildren > goalBudget && goalBudget > 0) {
                    yield FoldDecision.fold(
                            "📋 " + node.nodeType() + "(" + completedChildren + "/" + totalChildren + ")");
                }
                yield FoldDecision.skip("预算充足");
            }
        };
    }
}
