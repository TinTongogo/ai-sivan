package com.icusu.sivan.domain.orchestration;

/**
 * 单条路由条件表达式。
 *
 * <p>在 PhaseOutput 的结构化字段上做精确匹配：
 * <ul>
 *   <li>{@code sourceField="confidence"} → {@code getConfidence()}</li>
 *   <li>{@code sourceField="summary"} → {@code getSummary()}</li>
 *   <li>{@code sourceField="artifacts.<key>"} → artifacts map 中指定 key 的值</li>
 * </ul>
 *
 * @param sourceField 条件字段
 * @param operator    运算符 (equals/gte/lte/contains)
 * @param value       匹配值
 */
public record SingleCondition(String sourceField, String operator, String value) {
    public SingleCondition {
        if (sourceField == null || sourceField.isBlank()) {
            throw new IllegalArgumentException("sourceField 不能为空");
        }
        if (operator == null || operator.isBlank()) {
            throw new IllegalArgumentException("operator 不能为空");
        }
        if (value == null) {
            throw new IllegalArgumentException("value 不能为空");
        }
    }
}
