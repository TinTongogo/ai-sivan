package com.icusu.sivan.domain.compression;

import java.util.Map;

/** Token 预算 — 按内容类型分配的预算。 */
public record TokenBudget(
        int total,
        Map<String, Integer> allocation
) {
    public int forType(String type) {
        return allocation.getOrDefault(type, 0);
    }
}
