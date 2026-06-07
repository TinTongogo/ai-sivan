package com.icusu.sivan.agent.model;

import com.icusu.sivan.core.message.Msg;
import com.icusu.sivan.core.model.Model;
import com.icusu.sivan.core.model.ModelChunk;
import com.icusu.sivan.core.tool.ToolSpec;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * {LanguageModel} 重试包装：调用失败时自动重试。
 */
@Slf4j
public class RetryableModel implements Model {

    private final Model delegate;
    private final int maxAttempts;

    public RetryableModel(Model delegate) {
        this(delegate, 2);
    }

    public RetryableModel(Model delegate, int maxAttempts) {
        this.delegate = delegate;
        this.maxAttempts = maxAttempts;
    }

    @Override
    public String modelId() {
        return delegate.modelId();
    }

    @Override
    public Mono<ModelResponse> chat(List<Msg> messages, List<ToolSpec> tools, ModelParams params) {
        return retry(delegate.chat(messages, tools, params));
    }

    @Override
    public Flux<ModelChunk> stream(List<Msg> messages, List<ToolSpec> tools, ModelParams params) {
        return retry(delegate.stream(messages, tools, params));
    }

    @Override
    public Mono<List<Float>> embed(String text) {
        return retry(delegate.embed(text));
    }

    private <T> Mono<T> retry(Mono<T> source) {
        return source.retry(maxAttempts - 1)
                .doOnError(e -> log.warn("LLM 调用重试耗尽: model={}, attempts={}",
                        delegate.modelId(), maxAttempts, e));
    }

    private <T> Flux<T> retry(Flux<T> source) {
        return source.retry(maxAttempts - 1)
                .doOnError(e -> log.warn("LLM 流式调用重试耗尽: model={}, attempts={}",
                        delegate.modelId(), maxAttempts, e));
    }
}
