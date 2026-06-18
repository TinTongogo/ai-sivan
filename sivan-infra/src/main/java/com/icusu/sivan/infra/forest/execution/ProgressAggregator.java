package com.icusu.sivan.infra.forest.execution;

import com.icusu.sivan.common.Mode;
import com.icusu.sivan.common.NodeStatus;
import com.icusu.sivan.domain.forest.context.Progress;
import com.icusu.sivan.domain.forest.port.ProgressStrategy;
import com.icusu.sivan.domain.forest.tree.ExecutableNode;
import com.icusu.sivan.domain.forest.tree.TreeNode;
import com.icusu.sivan.infra.forest.strategy.*;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 进度聚合器 — 通过 ProgressStrategy Registry 按 Mode 分派。
 * <p>
 * 新增 Mode 只需新增一个 ProgressStrategy 实现，无需改聚合器。
 */
@Component
public class ProgressAggregator {

    private final Map<Mode, ProgressStrategy> strategies;

    public ProgressAggregator() {
        List<ProgressStrategy> strategyList = List.of(
                new SequentialProgressStrategy(),
                new ParallelProgressStrategy(),
                new ConditionalProgressStrategy(),
                new HierarchicalProgressStrategy(),
                new ConsensusProgressStrategy()
        );
        this.strategies = strategyList.stream()
                .collect(Collectors.toMap(ProgressStrategy::supportedMode, s -> s));
    }

    public Progress aggregate(TreeNode node) {
        if (node == null) return Progress.ZERO;
        return compute(node);
    }

    private Progress compute(TreeNode node) {
        Mode mode = node.mode();

        ProgressStrategy strategy = strategies.get(mode);
        if (strategy != null) {
            return strategy.compute(node, this::compute);
        }

        // NONE 等无对应策略的 mode，使用默认递归
        return computeDefault(node);
    }

    private Progress computeDefault(TreeNode node) {
        if (node.isLeaf()) {
            return ProgressStrategy.leafProgress(node);
        }
        int completed = 0, failed = 0, activated = 0, total = 0, depth = 0;
        for (TreeNode child : node.children()) {
            Progress cp = compute(child);
            completed += cp.completed();
            failed += cp.failed();
            activated += cp.activated();
            total += cp.total();
            depth = Math.max(depth, cp.depth());
        }
        return new Progress(completed, failed, activated, total, depth);
    }
}
