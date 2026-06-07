package com.icusu.sivan.domain.orchestration;

/**
 * 结构化路由条件 — 满足条件时执行 targetPhase。
 *
 * <p>替代 CONDITIONAL 模式下 LLM 截断文本路由，使用精确的结构化字段匹配。
 *
 * @param targetPhase 满足条件后执行的阶段索引
 * @param group       条件组（AND/OR）
 */
public record PhaseCondition(int targetPhase, ConditionGroup group) {
    public PhaseCondition {
        if (group == null) {
            throw new IllegalArgumentException("group 不能为空");
        }
    }
}
