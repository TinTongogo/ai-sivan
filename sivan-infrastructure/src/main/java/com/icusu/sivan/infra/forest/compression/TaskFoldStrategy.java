package com.icusu.sivan.infra.forest.compression;

import com.icusu.sivan.common.NodeStatus;
import com.icusu.sivan.domain.compression.FoldDecision;
import com.icusu.sivan.domain.compression.FoldStrategy;
import com.icusu.sivan.domain.compression.TokenBudget;
import com.icusu.sivan.domain.forest.tree.ContentNode;
import com.icusu.sivan.domain.forest.tree.TreeNode;

/**
 * TaskNode 折叠策略。
 * <p>
 * COMPLETED/FAILED/CANCELLED → 折叠为单行摘要；
 * RUNNING/PENDING → 保留。
 */
public class TaskFoldStrategy implements FoldStrategy {

    @Override
    public String supportedType() {
        return "task";
    }

    @Override
    public FoldDecision decide(TreeNode node, TokenBudget budget) {
        if (!(node instanceof ContentNode cn)) {
            return FoldDecision.skip("不是 ContentNode");
        }

        String content = cn.content();
        if (content == null || content.isBlank()) {
            return FoldDecision.skip("内容为空");
        }

        // 已完成/失败/已取消 → 折叠
        if (node instanceof com.icusu.sivan.domain.forest.tree.TaskNode task) {
            NodeStatus status = task.status();
            return switch (status) {
                case COMPLETED -> FoldDecision.fold("✅ " + truncate(content, 40));
                case FAILED -> FoldDecision.fold("❌ " + truncate(content, 40));
                case CANCELLED -> FoldDecision.fold("⛔ " + truncate(content, 40));
                default -> {
                    // RUNNING / PENDING: 检查 token 预算
                    long tokens = estimateTokens(content);
                    if (tokens > budget.forType("task")) {
                        yield FoldDecision.fold("📋 " + truncate(content, 30) + "...");
                    }
                    yield FoldDecision.skip("仍在执行或等待中");
                }
            };
        }

        return FoldDecision.skip("未知节点类型");
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private static long estimateTokens(String content) {
        return (long) (content.length() * 1.5);
    }
}
