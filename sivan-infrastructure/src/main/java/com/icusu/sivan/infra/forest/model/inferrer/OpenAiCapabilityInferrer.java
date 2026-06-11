package com.icusu.sivan.infra.forest.model.inferrer;

import com.icusu.sivan.domain.forest.service.CapabilityInferrer;
import com.icusu.sivan.domain.forest.service.ModelCapabilities;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * OpenAI 能力推断 — 精确/前缀匹配已知模型名。
 */
@Component
public class OpenAiCapabilityInferrer implements CapabilityInferrer {

    private static final Map<String, ModelCapabilities> KNOWN = Map.of(
        "gpt-4o", new ModelCapabilities("gpt-4o", "openai",
            Set.of(ModelCapabilities.Capability.STREAM, ModelCapabilities.Capability.TOOL_USE,
                   ModelCapabilities.Capability.VISION, ModelCapabilities.Capability.SYSTEM_PROMPT),
            4096, 0, 128000, java.util.List.of("function"), 2.5e-6, 1.0e-5),
        "gpt-4o-mini", new ModelCapabilities("gpt-4o-mini", "openai",
            Set.of(ModelCapabilities.Capability.STREAM, ModelCapabilities.Capability.TOOL_USE,
                   ModelCapabilities.Capability.VISION, ModelCapabilities.Capability.SYSTEM_PROMPT),
            4096, 0, 128000, java.util.List.of("function"), 1.5e-7, 6.0e-7),
        "o3-mini", new ModelCapabilities("o3-mini", "openai",
            Set.of(ModelCapabilities.Capability.STREAM, ModelCapabilities.Capability.TOOL_USE,
                   ModelCapabilities.Capability.SYSTEM_PROMPT),
            8192, 0, 200000, java.util.List.of("function"), 1.1e-6, 4.4e-6)
    );

    @Override
    public ModelCapabilities infer(String modelName) {
        if (modelName == null) return null;
        ModelCapabilities known = KNOWN.get(modelName);
        if (known != null) return known;
        if (modelName.startsWith("gpt-4o")) return KNOWN.get("gpt-4o").withModelId(modelName);
        if (modelName.startsWith("o3")) return KNOWN.get("o3-mini").withModelId(modelName);
        if (modelName.startsWith("gpt-4")) return defaultOpenAi(modelName, 4096, 8192);
        if (modelName.startsWith("gpt-3.5")) return defaultOpenAi(modelName, 2048, 4096);
        return null;
    }

    private static ModelCapabilities defaultOpenAi(String name, int maxOut, int maxIn) {
        return new ModelCapabilities(name, "openai",
            Set.of(ModelCapabilities.Capability.STREAM, ModelCapabilities.Capability.TOOL_USE,
                   ModelCapabilities.Capability.SYSTEM_PROMPT),
            maxOut, 0, maxIn, java.util.List.of("function"), 0, 0);
    }
}
