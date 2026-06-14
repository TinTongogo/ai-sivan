package com.icusu.sivan.infra.forest.model;

import com.icusu.sivan.domain.forest.event.ChatEvent;
import com.icusu.sivan.domain.shared.port.LanguageModel;
import com.icusu.sivan.domain.forest.vo.ModelCapabilities;
import com.icusu.sivan.domain.forest.vo.ModelParams;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Provider Adapter 基类 — 定义了从 LanguageModel 到 provider API 的转换骨架。
 * <p>
 * 子类只需实现：
 * <ul>
 *   <li>{@link #callProvider} — 发出 HTTP 请求，返回 provider 原生事件流</li>
 *   <li>{@link #toChatEvent} — 将 provider 原生事件转换为 {@link ChatEvent}</li>
 * </ul>
 */
public abstract class ModelProviderAdapter implements LanguageModel {

    private final String modelId;
    private final ModelCapabilities capabilities;

    protected ModelProviderAdapter(String modelId, ModelCapabilities capabilities) {
        this.modelId = modelId;
        this.capabilities = capabilities;
    }

    @Override
    public String modelId() {
        return modelId;
    }

    @Override
    public ModelCapabilities capabilities() {
        return capabilities;
    }

    @Override
    public Flux<ChatEvent> chat(List<Msg> messages, ModelParams params) {
        return Flux.concat(
                Mono.just(new ChatEvent.Started()),
                callProvider(messages, params)
                        .map(this::toChatEvent)
        );
    }

    /** 子类实现：发出 HTTP 请求，返回 provider 原生的响应流。 */
    protected abstract Flux<Object> callProvider(List<Msg> messages, ModelParams params);

    /** 子类实现：将 provider 原生事件转换为统一的 ChatEvent。 */
    protected abstract ChatEvent toChatEvent(Object nativeEvent);
}
