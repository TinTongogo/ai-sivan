package com.icusu.sivan.domain.forest.port;

import com.icusu.sivan.common.Mode;
import com.icusu.sivan.common.NodeStatus;
import com.icusu.sivan.domain.forest.context.Progress;
import com.icusu.sivan.domain.forest.tree.ExecutableNode;
import com.icusu.sivan.domain.forest.tree.TreeNode;

/**
 * 进度计算策略 — 每种 Mode 有不同的进度聚合规则。
 * <p>
 * 差异点：
 * <ul>
 *   <li>SEQUENTIAL / PARALLEL / HIERARCHICAL / CONSENSUS：全部节点计数</li>
 *   <li>CONDITIONAL：PENDING 节点不计入已触及（untouched），只有 RUNNING 或 COMPLETED 才计入</li>
 * </ul>
 * <p>
 * 由 {@link ProgressAggregator} 通过 Registry 查表调用，新增 Mode 只需新增实现。
 */

public interface ProgressStrategy {

    /** 本策略适用的 Mode。 */
    Mode supportedMode();

    /**
     * 计算子树进度。只计算本节点及其 children 的进度（递归由调用方控制）。
     *
     * @param node    当前节点
     * @param recurse 递归回调：计算子树的进度
     * @return 进度结果
     */
    Progress compute(TreeNode node, java.util.function.Function<TreeNode, Progress> recurse);

    /**
     * 默认的叶子节点判断规则：
     * <ul>
     *   <li>COMPLETED → (1, 0, 1, 1, 0) 完成</li>
     *   <li>RUNNING → (0, 0, 1, 1, 0) 执行中</li>
     *   <li>FAILED → (0, 1, 1, 1, 0) 已失败</li>
     *   <li>CANCELLED → (0, 0, 0, 1, 0) 已取消（不计入激活）</li>
     *   <li>PENDING → (0, 0, 0, 1, 0) 未触及</li>
     * </ul>
     */
    static Progress leafProgress(TreeNode node) {
        return switch (NodeStatusOf(node)) {
            case COMPLETED -> new Progress(1, 0, 1, 1, 0);
            case RUNNING   -> new Progress(0, 0, 1, 1, 0);
            case FAILED    -> new Progress(0, 1, 1, 1, 0);
            case CANCELLED -> new Progress(0, 0, 0, 1, 0);
            default        -> new Progress(0, 0, 0, 1, 0); // PENDING / FOLDED
        };
    }

    /** 安全的 status 提取。 */
    static com.icusu.sivan.common.NodeStatus NodeStatusOf(TreeNode node) {
        if (node instanceof ExecutableNode e) {
            return e.status();
        }
        return NodeStatus.PENDING;
    }
}
