package com.icusu.sivan.infra.forest.model;

import com.icusu.sivan.domain.forest.service.LanguageModel;
import com.icusu.sivan.domain.forest.service.ModelCapabilities;
import com.icusu.sivan.domain.forest.service.ModelCapabilities.Capability;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 模型注册中心 — 按 modelId 注册和查找 LanguageModel。
 * <p>
 * V2.0 模型注册表，与 V1.0 DefaultModelRouter 并存。
 */
@Component
public class ModelRegistry {

    private final Map<String, LanguageModel> models = new ConcurrentHashMap<>();

    public void register(LanguageModel model) {
        models.put(model.modelId(), model);
    }

    public LanguageModel get(String modelId) {
        LanguageModel model = models.get(modelId);
        if (model == null) {
            throw new IllegalArgumentException("模型未注册: " + modelId);
        }
        return model;
    }

    public List<LanguageModel> findByCapability(Capability capability) {
        return models.values().stream()
                .filter(m -> m.capabilities().supported().contains(capability))
                .toList();
    }

    public List<LanguageModel> all() {
        return List.copyOf(models.values());
    }

    public boolean contains(String modelId) {
        return models.containsKey(modelId);
    }
}
