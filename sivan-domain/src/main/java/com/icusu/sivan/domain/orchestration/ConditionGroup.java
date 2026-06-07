package com.icusu.sivan.domain.orchestration;

import java.util.List;

/**
 * 条件组 — 一组条件按 AND/OR 组合求值。
 *
 * @param conditions 条件列表
 * @param op         组合逻辑（AND / OR）
 */
public record ConditionGroup(List<SingleCondition> conditions, LogicOp op) {
    public ConditionGroup {
        if (conditions == null || conditions.isEmpty()) {
            throw new IllegalArgumentException("conditions 不能为空");
        }
        if (op == null) {
            op = LogicOp.AND;
        }
    }
}
