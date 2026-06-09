package com.icusu.sivan.domain.forest.service;

import java.util.List;
import java.util.Set;

/**
 * 模型能力声明 — 由 ProviderAdapter 在初始化时填充，不可变。
 */
public record ModelCapabilities(
        String modelId,
        String provider,
        Set<Capability> supported,
        int maxTokens,
        int maxThinkingTokens,
        int maxInput,
        List<String> supportedToolSchemas,
        double inputPricePer1k,
        double outputPricePer1k
) {
    public ModelCapabilities withModelId(String modelId) {
        return new ModelCapabilities(modelId, provider, supported, maxTokens, maxThinkingTokens,
                maxInput, supportedToolSchemas, inputPricePer1k, outputPricePer1k);
    }

    public enum Capability {
        STREAM, THINKING, TOOL_USE, VISION, SYSTEM_PROMPT, JSON_MODE, BATCH
    }
}
