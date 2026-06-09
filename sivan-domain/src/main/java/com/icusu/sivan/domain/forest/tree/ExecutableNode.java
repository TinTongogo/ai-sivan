package com.icusu.sivan.domain.forest.tree;

import com.icusu.sivan.common.Mode;
import com.icusu.sivan.common.NodeStatus;

/**
 * 可执行节点 — 有编排模式 {@link #mode()} 和生命周期状态 {@link #status()}。
 * <p>
 * 只有 ExecutableNode 子类型才会被 {@code ForestExecutor} 驱动执行。
 * ContentNode 子类型跳过执行遍历。
 */
public interface ExecutableNode extends TreeNode {

    @Override
    default boolean isExecutable() {
        return true;
    }

    /** 编排模式 */
    Mode mode();

    /** 当前生命周期状态 */
    NodeStatus status();

    /** 设置状态（执行器在运行过程中推进状态） */
    void setStatus(NodeStatus status);

    /** 当状态为 RUNNING 时表示本轮执行可推进 */
    default boolean isCompletable() {
        return status() == NodeStatus.RUNNING;
    }
}
