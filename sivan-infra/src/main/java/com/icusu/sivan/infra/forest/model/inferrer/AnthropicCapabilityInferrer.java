package com.icusu.sivan.infra.forest.model.inferrer;

import com.icusu.sivan.domain.shared.port.CapabilityInferrer;
import com.icusu.sivan.domain.forest.vo.ModelCapabilities;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Anthropic 能力推断 — Claude 模型名前缀匹配。
 */
@Component
public class AnthropicCapabilityInferrer implements CapabilityInferrer {

    @Override
    public ModelCapabilities infer(String modelName) {
        if (modelName == null) return null;
        if (modelName.startsWith("claude-opus-4")) {
            return new ModelCapabilities(modelName, "anthropic",
                Set.of(ModelCapabilities.Capability.STREAM, ModelCapabilities.Capability.THINKING,
                       ModelCapabilities.Capability.TOOL_USE, ModelCapabilities.Capability.VISION,
                       ModelCapabilities.Capability.SYSTEM_PROMPT),
                8192, 32000, 200000, java.util.List.of("function"), 1.5e-5, 7.5e-5);
        }
        if (modelName.startsWith("claude-sonnet-4")) {
            return new ModelCapabilities(modelName, "anthropic",
                Set.of(ModelCapabilities.Capability.STREAM, ModelCapabilities.Capability.THINKING,
                       ModelCapabilities.Capability.TOOL_USE, ModelCapabilities.Capability.VISION,
                       ModelCapabilities.Capability.SYSTEM_PROMPT),
                8192, 16000, 200000, java.util.List.of("function"), 3.0e-6, 1.5e-5);
        }
        if (modelName.startsWith("claude")) {
            return new ModelCapabilities(modelName, "anthropic",
                Set.of(ModelCapabilities.Capability.STREAM, ModelCapabilities.Capability.TOOL_USE,
                       ModelCapabilities.Capability.SYSTEM_PROMPT),
                4096, 0, 100000, java.util.List.of("function"), 0, 0);
        }
        return null;
    }
}
