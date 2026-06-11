package com.icusu.sivan.infra.forest.model.decorator;

import com.icusu.sivan.domain.forest.service.ChatEvent;
import com.icusu.sivan.domain.forest.service.LanguageModel;
import com.icusu.sivan.domain.forest.service.ModelCapabilities;
import com.icusu.sivan.domain.forest.service.ModelParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 回退装饰器 — 主模型失败时自动切换到备用模型。
 * <p>
 * 对调用方透明，失败后自动切换到 fallback 模型重试。
 */
public class FallbackDecorator implements LanguageModel {

    private static final Logger log = LoggerFactory.getLogger(FallbackDecorator.class);

    private final LanguageModel primary;
    private final LanguageModel fallback;

    public FallbackDecorator(LanguageModel primary, LanguageModel fallback) {
        this.primary = primary;
        this.fallback = fallback;
    }

    @Override
    public String modelId() { return primary.modelId(); }

    @Override
    public ModelCapabilities capabilities() { return primary.capabilities(); }

    @Override
    public Flux<ChatEvent> chat(List<LanguageModel.Msg> messages, ModelParams params) {
        return primary.chat(messages, params)
                .onErrorResume(throwable -> {
                    log.warn("[回退] {} 调用失败, 切换到 {}: {}",
                            primary.modelId(), fallback.modelId(), throwable.getMessage());
                    return fallback.chat(messages, params);
                });
    }

    @Override
    public String toString() { return "FallbackDecorator(" + primary + " -> " + fallback + ")"; }
}
