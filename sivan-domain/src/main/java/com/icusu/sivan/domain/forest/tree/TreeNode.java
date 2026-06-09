package com.icusu.sivan.domain.forest.tree;

import com.icusu.sivan.domain.forest.service.ForestVisitor;

import java.util.List;

/**
 * 树结构接口 — 所有节点都需要。
 * <p>
 * 这是递归树的原子单元：每个节点持有父引用和子列表，
 * 遍历器通过 {@link #children()} 递归下降。
 */
public interface TreeNode {

    /** 节点唯一 ID */
    String nodeId();

    /** 父节点，null 表示根 */
    TreeNode parent();

    /** 设置父节点（构建树时由 addChild 调用） */
    void setParent(TreeNode parent);

    /** 子节点列表，空列表表示叶子 */
    List<TreeNode> children();

    /** 在兄弟节点中的序号 */
    int order();

    /** 设置兄弟序号（构建树时由 addChild 调用） */
    void setOrder(int order);

    default boolean isLeaf() {
        return children().isEmpty();
    }

    /**
     * 节点是否是 ExecutableNode — 避免编排策略中 instanceof 检查。
     * ExecutableNode 子类应覆写返回 true。
     */
    default boolean isExecutable() {
        return false;
    }

    /**
     * 节点类型标识 — 与 {@code forest_nodes.node_type} 对应。
     * 各实现类应覆写返回自己的类型名。
     */
    default String nodeType() {
        return this.getClass().getSimpleName();
    }

    /**
     * 接受访问者 — 将具体节点类型委派到 {@link ForestVisitor#visitTask(TaskNode)} 等 typed 方法。
     * <p>
     * 各实现类应：{@code visitor.visitXxx(this)}。
     * 替代 {@code instanceof} 运行时判断。
     */
    void accept(ForestVisitor visitor);
}
