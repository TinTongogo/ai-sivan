package com.icusu.sivan.infra.forest.model;

import com.icusu.sivan.domain.shared.port.CapabilityInferrer;
import com.icusu.sivan.domain.shared.port.LanguageModel;
import com.icusu.sivan.domain.forest.vo.ModelCapabilities;
import com.icusu.sivan.domain.forest.vo.ModelCapabilities.Capability;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模型注册中心 — 按 modelId 注册和查找 LanguageModel。
 * <p>
 * V2.0 模型注册表，通过 {@link CapabilityInferrer} 策略推断模型能力。
 */
@Component
public class ModelRegistry {

    private static final Logger log = LoggerFactory.getLogger(ModelRegistry.class);

    private final Map<String, LanguageModel> models = new ConcurrentHashMap<>();
    private final List<CapabilityInferrer> inferrers;

    public ModelRegistry(List<CapabilityInferrer> inferrers) {
        this.inferrers = inferrers;
    }

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

    /** 按单个能力过滤。 */
    public List<LanguageModel> findByCapability(Capability capability) {
        return models.values().stream()
                .filter(m -> m.capabilities().supported().contains(capability))
                .toList();
    }

    /** 按多个能力 AND 过滤（设计文档 4.1 节）。 */
    public List<LanguageModel> findByCapabilities(Set<Capability> required) {
        return models.values().stream()
                .filter(m -> m.capabilities().supported().containsAll(required))
                .toList();
    }

    /**
     * 根据模型名从注册的 Inferrer 链推断能力声明。
     * Stratgy 模式：按注册顺序遍历 Inferrer，首个非 null 结果即最终能力。
     */
    public ModelCapabilities inferCapability(String modelName) {
        for (CapabilityInferrer inferrer : inferrers) {
            try {
                ModelCapabilities caps = inferrer.infer(modelName);
                if (caps != null) {
                    if (log.isDebugEnabled()) {
                        log.debug("[能力推断] model={} provider={} from {}",
                                modelName, caps.provider(), inferrer.getClass().getSimpleName());
                    }
                    return caps;
                }
            } catch (Exception e) {
                log.warn("[能力推断] {} 推断失败: {}", inferrer.getClass().getSimpleName(), e.getMessage());
            }
        }
        log.warn("[能力推断] 未知模型，返回最简能力: model={}", modelName);
        return new ModelCapabilities(modelName, "unknown",
                Set.of(Capability.STREAM, Capability.SYSTEM_PROMPT), 2048, 0, 8192, List.of(), 0, 0);
    }

    public List<LanguageModel> all() {
        return List.copyOf(models.values());
    }

    public boolean contains(String modelId) {
        return models.containsKey(modelId);
    }
}
