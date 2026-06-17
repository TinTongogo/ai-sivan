package com.icusu.sivan.domain.forest.tree;

/**
 * 可压缩节点 — 参与折叠决策，提供 token 估算。
 * <p>
 * 压缩器遍历树时只关心实现了此接口的节点。
 */
public interface CompressibleNode extends TreeNode {

    /** 重要性 [0, 1]，越高越不可丢弃 */
    double importance();

    /** 本节点（不含子节点）的 token 估算 */
    long estimateSelfTokens();

    /** 本节点及其所有子孙的 token 估算，-1 表示未计算或已失效 */
    long estimateSubtreeTokens();

    /** 使子树 token 缓存失效 */
    default void invalidateTokenCache() {}

    /** 重要性低于阈值时可折叠 */
    default boolean isFoldable() {
        return importance() < 0.3;
    }

    /** 节点 status 变更时增量更新祖先链的 token 缓存（O(log n)） */
    default void onStatusChanged() {
        invalidateTokenCache();
        TreeNode p = parent();
        if (p != null) p.invalidateAncestorTokenCache();
    }

    /** 向上传播 token 缓存失效 — 覆写 {@link TreeNode#invalidateAncestorTokenCache()}。 */
    @Override
    default void invalidateAncestorTokenCache() {
        invalidateTokenCache();
        TreeNode p = parent();
        if (p != null) p.invalidateAncestorTokenCache();
    }
}
