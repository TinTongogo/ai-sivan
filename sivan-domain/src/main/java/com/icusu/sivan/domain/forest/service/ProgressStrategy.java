package com.icusu.sivan.domain.forest.service;

import com.icusu.sivan.domain.forest.context.Progress;
import com.icusu.sivan.domain.forest.tree.TreeNode;

/** 进度计算策略接口 — 每种 Mode 一种实现。 */
@FunctionalInterface
public interface ProgressStrategy {
    Progress compute(TreeNode node);
}
