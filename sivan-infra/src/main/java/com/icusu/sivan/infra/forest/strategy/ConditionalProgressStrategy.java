package com.icusu.sivan.infra.forest.strategy;

import com.icusu.sivan.common.Mode;
import com.icusu.sivan.common.NodeStatus;
import com.icusu.sivan.domain.forest.context.Progress;
import com.icusu.sivan.domain.forest.port.ProgressStrategy;
import com.icusu.sivan.domain.forest.tree.ExecutableNode;
import com.icusu.sivan.domain.forest.tree.TreeNode;

import java.util.function.Function;

/**
 * CONDITIONAL 进度策略 — 只计算已触及的阶段。
 * <p>
 * 规则：PENDING 状态的叶子不计入 activated（未触及），
 * 只有 RUNNING / COMPLETED 才算已激活。
 * 内部节点同样递归，只有已触及的子节点才计入。
 */
public class ConditionalProgressStrategy implements ProgressStrategy {

    @Override
    public Mode supportedMode() {
        return Mode.CONDITIONAL;
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
            NodeStatus st = child.status();
            if (st == NodeStatus.PENDING) {
                // 未触及的条件分支：只计入 total，不计入 activated
                Progress sub = recurse.apply(child);
                total += sub.total();
                depth = Math.max(depth, sub.depth() + 1);
                continue;
            }
            Progress cp = recurse.apply(child);
            completed += cp.completed();
            activated += cp.activated();
            total += cp.total();
            depth = Math.max(depth, cp.depth() + 1);
        }
        return new Progress(completed, activated, total, depth);
    }
}
