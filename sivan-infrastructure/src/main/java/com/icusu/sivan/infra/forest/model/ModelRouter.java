package com.icusu.sivan.infra.forest.model;

import com.icusu.sivan.domain.forest.service.ChatEvent;
import com.icusu.sivan.domain.forest.service.LanguageModel;
import com.icusu.sivan.domain.forest.service.ModelParams;
import com.icusu.sivan.domain.forest.service.TaskProfile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;

import static com.icusu.sivan.domain.forest.service.ModelCapabilities.Capability.*;

/**
 * 模型路由器 — 根据任务特征选择最优模型。
 * <p>
 * V2.0 模型路由器，与 V1.0 DefaultModelRouter 并存。
 */
@Component
public class ModelRouter {

    private final ModelRegistry registry;
    private final String defaultModelId;
    private final String fallbackModelId;

    public ModelRouter(ModelRegistry registry,
                       @Value("${sivan.models.routing.default:echo}") String defaultModelId,
                       @Value("${sivan.models.routing.fallback:echo}") String fallbackModelId) {
        this.registry = registry;
        this.defaultModelId = defaultModelId;
        this.fallbackModelId = fallbackModelId;
    }

    public LanguageModel defaultModel() {
        return registry.get(defaultModelId);
    }

    public LanguageModel forTask(TaskProfile task) {
        LanguageModel model;
        if (task.requiresThinking()) {
            model = selectBest(registry.findByCapability(THINKING), task);
            if (model != null) return model;
        }
        if (task.requiresVision()) {
            model = selectBest(registry.findByCapability(VISION), task);
            if (model != null) return model;
        }
        if (task.isSimple()) {
            model = selectCheapest(registry.findByCapability(STREAM));
            if (model != null) return model;
        }
        try { return registry.get(defaultModelId); } catch (Exception ignored) {}
        try { return registry.get(fallbackModelId); } catch (Exception ignored) {}
        var all = registry.all();
        if (!all.isEmpty()) return all.getFirst();
        throw new IllegalStateException("无可用模型");
    }

    public Flux<ChatEvent> chatWithFallback(List<LanguageModel.Msg> messages, ModelParams params, TaskProfile task) {
        LanguageModel primary = forTask(task);
        return primary.chat(messages, params)
                .onErrorResume(throwable -> {
                    LanguageModel fallback = registry.get(fallbackModelId);
                    return fallback.chat(messages, params);
                });
    }

    private LanguageModel selectBest(List<LanguageModel> candidates, TaskProfile task) {
        if (candidates.isEmpty()) return null;
        return candidates.getFirst();
    }

    private LanguageModel selectCheapest(List<LanguageModel> candidates) {
        if (candidates.isEmpty()) return null;
        return candidates.stream()
                .min((a, b) -> Double.compare(
                        a.capabilities().inputPricePer1k(),
                        b.capabilities().inputPricePer1k()))
                .orElse(null);
    }
}
