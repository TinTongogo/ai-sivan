package com.icusu.sivan.agent.model;

import com.icusu.sivan.core.message.Msg;
import com.icusu.sivan.core.model.Model;
import com.icusu.sivan.core.model.ModelChunk;
import com.icusu.sivan.core.model.TokenUsage;
import com.icusu.sivan.core.tool.ToolSpec;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.function.BiConsumer;

/**
 * {LanguageModel} 用量追踪包装。
 * <p>
 * 每次 LLM 调用完成后调用 consumer 记录用量。
 */
@Slf4j
public class UsageTrackingModel implements Model {

    private final Model delegate;
    private final BiConsumer<TokenUsage, String> usageConsumer;

    public UsageTrackingModel(Model delegate,
                              BiConsumer<TokenUsage, String> usageConsumer) {
        this.delegate = delegate;
        this.usageConsumer = usageConsumer;
    }

    @Override
    public String modelId() {
        return delegate.modelId();
    }

    @Override
    public Mono<ModelResponse> chat(List<Msg> messages, List<ToolSpec> tools, ModelParams params) {
        return delegate.chat(messages, tools, params)
                .doOnNext(response -> {
                    if (response.usage() != null) {
                        usageConsumer.accept(response.usage(), delegate.modelId());
                    }
                });
    }

    @Override
    public Flux<ModelChunk> stream(List<Msg> messages, List<ToolSpec> tools, ModelParams params) {
        return delegate.stream(messages, tools, params);
    }

    @Override
    public Mono<List<Float>> embed(String text) {
        return delegate.embed(text);
    }
}
