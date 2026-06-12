package com.icusu.sivan.domain.compression;

import java.util.Map;

/**
 * Token 预算分配策略 — 按场景和内容类型分配预算。
 * <p>
 * 设计文档 4.1 节。实现 Strategy 模式，可扩展新场景。
 */

public interface BudgetAllocationStrategy {

    /** 返回 {类型 → token 预算} 映射。 */
    Map<String, Integer> allocate(int totalBudget);

    /** 适用的场景。 */
    String scene();
}
