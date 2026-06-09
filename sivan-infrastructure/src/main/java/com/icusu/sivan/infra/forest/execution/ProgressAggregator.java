package com.icusu.sivan.infra.forest.execution;

import com.icusu.sivan.common.NodeStatus;
import com.icusu.sivan.domain.forest.context.Progress;
import com.icusu.sivan.domain.forest.tree.ExecutableNode;
import com.icusu.sivan.domain.forest.tree.TreeNode;
import org.springframework.stereotype.Component;

/**
 * 进度聚合器 — 递归计算树中各状态节点的数量。
 */
@Component
public class ProgressAggregator {

    public Progress aggregate(TreeNode node) {
        if (node == null) return Progress.ZERO;
        return compute(node, 0);
    }

    private Progress compute(TreeNode node, int depth) {
        NodeStatus st = node instanceof ExecutableNode e ? e.status() : NodeStatus.PENDING;
        int completed = st == NodeStatus.COMPLETED ? 1 : 0;
        int activated = (st != NodeStatus.PENDING && st != NodeStatus.FOLDED) ? 1 : 0;
        int total = 1;
        int maxDepth = depth;

        for (TreeNode child : node.children()) {
            Progress childProgress = compute(child, depth + 1);
            completed += childProgress.completed();
            activated += childProgress.activated();
            total += childProgress.total();
            maxDepth = Math.max(maxDepth, childProgress.depth());
        }

        return new Progress(completed, activated, total, maxDepth);
    }

    private int nodeStatusCompleted(TreeNode node) {
        try {
            var status = (NodeStatus) node.getClass().getMethod("status").invoke(node);
            return status == NodeStatus.COMPLETED ? 1 : 0;
        } catch (Exception e) {
            return 0;
        }
    }
}
