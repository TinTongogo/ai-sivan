package com.icusu.sivan.domain.forest.service;

import java.util.Map;

/**
 * 模型调用参数。所有字段可选，缺失由默认值填充。
 */
public record ModelParams(
        Double temperature,
        Integer maxTokens,
        Integer thinkingTokens,
        Map<String, Object> extra
) {
    public static ModelParams defaults() {
        return new ModelParams(0.7, 4096, 0, Map.of());
    }

    public ModelParams withTemperature(double t) {
        return new ModelParams(t, maxTokens, thinkingTokens, extra);
    }

    public ModelParams withMaxTokens(int m) {
        return new ModelParams(temperature, m, thinkingTokens, extra);
    }

    public ModelParams withThinking(int t) {
        return new ModelParams(temperature, maxTokens, t, extra);
    }
}
