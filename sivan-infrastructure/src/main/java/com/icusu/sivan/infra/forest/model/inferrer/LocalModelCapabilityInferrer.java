package com.icusu.sivan.infra.forest.model.inferrer;

import com.icusu.sivan.domain.forest.service.CapabilityInferrer;
import com.icusu.sivan.domain.forest.service.ModelCapabilities;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 本地模型能力推断 — Ollama / vLLM / DeepSeek 等通过 OpenAI 兼容接口的模型。
 * <p>
 * DeepSeek、Qwen 等模型有扩展能力（THINKING、TOOL_USE），按前缀匹配。
 */
@Component
public class LocalModelCapabilityInferrer implements CapabilityInferrer {

    @Override
    public ModelCapabilities infer(String modelName) {
        if (modelName == null) return null;
        String lower = modelName.toLowerCase();

        // DeepSeek 系列
        if (lower.startsWith("deepseek-v4")) {
            return new ModelCapabilities(modelName, "local",
                Set.of(ModelCapabilities.Capability.STREAM, ModelCapabilities.Capability.THINKING,
                       ModelCapabilities.Capability.TOOL_USE, ModelCapabilities.Capability.SYSTEM_PROMPT),
                8192, 16000, 64000, java.util.List.of(), 0, 0);
        }
        if (lower.startsWith("deepseek")) {
            return new ModelCapabilities(modelName, "local",
                Set.of(ModelCapabilities.Capability.STREAM, ModelCapabilities.Capability.THINKING,
                       ModelCapabilities.Capability.SYSTEM_PROMPT),
                4096, 8000, 32768, java.util.List.of(), 0, 0);
        }

        // Qwen 系列
        if (lower.startsWith("qwen3")) {
            return new ModelCapabilities(modelName, "local",
                Set.of(ModelCapabilities.Capability.STREAM, ModelCapabilities.Capability.THINKING,
                       ModelCapabilities.Capability.TOOL_USE, ModelCapabilities.Capability.SYSTEM_PROMPT),
                4096, 8000, 32768, java.util.List.of(), 0, 0);
        }
        if (lower.startsWith("qwen")) {
            return new ModelCapabilities(modelName, "local",
                Set.of(ModelCapabilities.Capability.STREAM, ModelCapabilities.Capability.TOOL_USE,
                       ModelCapabilities.Capability.SYSTEM_PROMPT),
                2048, 0, 32768, java.util.List.of(), 0, 0);
        }

        // Llama / Gemma 通用
        if (lower.startsWith("llama") || lower.startsWith("gemma")) {
            return new ModelCapabilities(modelName, "local",
                Set.of(ModelCapabilities.Capability.STREAM, ModelCapabilities.Capability.TOOL_USE,
                       ModelCapabilities.Capability.SYSTEM_PROMPT),
                2048, 0, 32768, java.util.List.of(), 0, 0);
        }

        // 未知本地模型：最保守能力
        return new ModelCapabilities(modelName, "local",
            Set.of(ModelCapabilities.Capability.STREAM, ModelCapabilities.Capability.SYSTEM_PROMPT),
            2048, 0, 8192, java.util.List.of(), 0, 0);
    }
}
