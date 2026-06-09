package com.icusu.sivan.domain.compression;

import com.icusu.sivan.domain.forest.tree.TreeNode;

/** 节点折叠策略 — 每种节点类型一个实现。 */
public interface FoldStrategy {
    String supportedType();
    FoldDecision decide(TreeNode node, TokenBudget budget);
}
