package com.icusu.sivan.infra.forest.model;

import com.icusu.sivan.domain.forest.event.ChatEvent;
import com.icusu.sivan.domain.forest.vo.Intent;
import com.icusu.sivan.domain.shared.port.LanguageModel;
import com.icusu.sivan.domain.forest.vo.ModelParams;
import com.icusu.sivan.domain.forest.vo.TaskProfile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.Comparator;
import java.util.List;

import static com.icusu.sivan.domain.forest.vo.ModelCapabilities.Capability.*;

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

    /**
     * 按调用场景选择模型（设计文档 5.1 节）。
     * <p>
     * TODO: 待接入。当前所有调用方走 {@link #defaultModel()} / {@link #forTask(TaskProfile)}，
     * 路由结果中的 intent 尚未映射到此方法。
     * 接入条件：1) 调用方持有路由 intent；2) 将 String "chat"/"task" 映射为 {@link Intent} 枚举。
     */
    public LanguageModel forIntent(Intent intent) {
        return switch (intent) {
            case CHAT -> registry.get(defaultModelId);
            case CREATIVE -> {
                // 创意场景优先高思考 token 上限的模型
                var candidates = registry.findByCapability(THINKING);
                yield candidates.isEmpty() ? registry.get(defaultModelId) : candidates.getFirst();
            }
            case ANALYSIS -> {
                // 分析场景优先高 maxToken 的模型
                var all = registry.all();
                yield all.stream().max(Comparator.comparingInt(a -> a.capabilities().maxTokens()))
                        .orElse(registry.get(defaultModelId));
            }
        };
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
                .min(Comparator.comparingDouble(a -> a.capabilities().inputPricePer1k()))
                .orElse(null);
    }
}
