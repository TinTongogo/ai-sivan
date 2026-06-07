package com.icusu.sivan.agent.model;

import com.icusu.sivan.domain.model.ModelCapability;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 模型能力注册表。
 * 优先按模型名前缀匹配，未知模型回退到 providerType 默认值。
 */
@Slf4j
@Component
public class ModelCapabilityRegistry {

    private Map<String, Set<ModelCapability>> providerTypeDefaults;
    private LinkedHashMap<String, Set<ModelCapability>> modelPatterns;

    @PostConstruct
    void init() {
        // providerType 兜底默认值
        providerTypeDefaults = Map.of(
                "openai", EnumSet.of(ModelCapability.VISION, ModelCapability.AUDIO, ModelCapability.TOOL_USE, ModelCapability.STREAMING, ModelCapability.REASONING_EFFORT),
                "anthropic", EnumSet.of(ModelCapability.VISION, ModelCapability.TOOL_USE, ModelCapability.STREAMING, ModelCapability.THINKING),
                "openai-compatible", EnumSet.noneOf(ModelCapability.class)
        );

        // 模型名模式匹配（前缀匹配，注册顺序即优先级）
        modelPatterns = new LinkedHashMap<>();
        // OpenAI 模型
        modelPatterns.put("gpt-4o", EnumSet.of(ModelCapability.VISION, ModelCapability.AUDIO, ModelCapability.TOOL_USE, ModelCapability.STREAMING, ModelCapability.REASONING_EFFORT));
        modelPatterns.put("gpt-4.1", EnumSet.of(ModelCapability.VISION, ModelCapability.AUDIO, ModelCapability.TOOL_USE, ModelCapability.STREAMING, ModelCapability.REASONING_EFFORT));
        modelPatterns.put("gpt-4-turbo", EnumSet.of(ModelCapability.VISION, ModelCapability.TOOL_USE, ModelCapability.STREAMING));
        modelPatterns.put("gpt-4", EnumSet.of(ModelCapability.TOOL_USE, ModelCapability.STREAMING));
        modelPatterns.put("gpt-3.5", EnumSet.of(ModelCapability.TOOL_USE, ModelCapability.STREAMING));
        modelPatterns.put("o3", EnumSet.of(ModelCapability.AUDIO, ModelCapability.STREAMING, ModelCapability.REASONING_EFFORT));
        modelPatterns.put("o1", EnumSet.of(ModelCapability.STREAMING, ModelCapability.REASONING_EFFORT));
        // Anthropic 模型
        modelPatterns.put("claude-opus", EnumSet.of(ModelCapability.VISION, ModelCapability.TOOL_USE, ModelCapability.STREAMING, ModelCapability.THINKING));
        modelPatterns.put("claude-sonnet", EnumSet.of(ModelCapability.VISION, ModelCapability.TOOL_USE, ModelCapability.STREAMING, ModelCapability.THINKING));
        modelPatterns.put("claude-haiku", EnumSet.of(ModelCapability.VISION, ModelCapability.TOOL_USE, ModelCapability.STREAMING));
        // DeepSeek 模型
        modelPatterns.put("deepseek-v4-flash", EnumSet.of(ModelCapability.STREAMING, ModelCapability.THINKING, ModelCapability.REASONING_EFFORT));
        modelPatterns.put("deepseek-v4-pro", EnumSet.of(ModelCapability.STREAMING, ModelCapability.THINKING, ModelCapability.REASONING_EFFORT));
        modelPatterns.put("deepseek-v3", EnumSet.of(ModelCapability.STREAMING, ModelCapability.THINKING));
        modelPatterns.put("deepseek-r1", EnumSet.of(ModelCapability.STREAMING, ModelCapability.THINKING, ModelCapability.REASONING_EFFORT));
        // Qwen 模型
        modelPatterns.put("qwen3", EnumSet.of(ModelCapability.TOOL_USE, ModelCapability.STREAMING, ModelCapability.THINKING));
        modelPatterns.put("qwen2.5-vl", EnumSet.of(ModelCapability.VISION, ModelCapability.STREAMING));
        // Gemma 模型
        modelPatterns.put("gemma", EnumSet.of(ModelCapability.VISION, ModelCapability.AUDIO, ModelCapability.TOOL_USE, ModelCapability.STREAMING));
        // Llama 模型
        modelPatterns.put("llama", EnumSet.of(ModelCapability.TOOL_USE, ModelCapability.STREAMING));

        log.info("模型能力注册表已就绪: {} 条名前缀匹配规则, {} 个提供商默认配置", modelPatterns.size(), providerTypeDefaults.size());
    }

    /**
     * 根据模型名和 providerType 推断能力集（模型名前缀匹配优先，未命中回退 providerType 默认值）。
     */
    public Set<ModelCapability> infer(String modelName, String providerType) {
        if (modelName != null && !modelName.isBlank()) {
            String lower = modelName.toLowerCase().strip();
            for (var entry : modelPatterns.entrySet()) {
                if (lower.startsWith(entry.getKey())) {
                    return entry.getValue();
                }
            }
        }
        return getDefaults(providerType);
    }

    /**
     * 获取指定 providerType 的默认能力集（兜底）。
     */
    public Set<ModelCapability> getDefaults(String providerType) {
        return providerTypeDefaults.getOrDefault(providerType, EnumSet.noneOf(ModelCapability.class));
    }
}
