package com.icusu.sivan.infra.forest.strategy;

import com.icusu.sivan.common.Mode;
import com.icusu.sivan.domain.forest.context.Progress;
import com.icusu.sivan.domain.forest.port.ProgressStrategy;
import com.icusu.sivan.domain.forest.tree.TreeNode;

import java.util.function.Function;

/**
 * SEQUENTIAL 进度策略 — 全部子节点参与计数，PENDING 算未触及。
 * <p>
 * 规则：叶子节点按 {@link ProgressStrategy#leafProgress} 计算；
 * 内部节点递归聚合 children，PENDING 不计入 activated。
 */
public class SequentialProgressStrategy implements ProgressStrategy {

    @Override
    public Mode supportedMode() {
        return Mode.SEQUENTIAL;
    }

    @Override
    public Progress compute(TreeNode node, Function<TreeNode, Progress> recurse) {
        if (node.isLeaf()) {
            return ProgressStrategy.leafProgress(node);
        }
        // 先算自身贡献
        Progress self = ProgressStrategy.leafProgress(node);
        int completed = self.completed();
        int activated = self.activated();
        int total = self.total();
        int depth = 0;

        for (TreeNode child : node.children()) {
            Progress cp = recurse.apply(child);
            completed += cp.completed();
            activated += cp.activated();
            total += cp.total();
            depth = Math.max(depth, cp.depth() + 1);
        }
        return new Progress(completed, activated, total, depth);
    }
}
