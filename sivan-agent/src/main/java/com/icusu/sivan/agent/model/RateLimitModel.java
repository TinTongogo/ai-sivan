package com.icusu.sivan.agent.model;

import com.icusu.sivan.core.message.Msg;
import com.icusu.sivan.core.model.Model;

import com.icusu.sivan.core.model.ModelChunk;
import com.icusu.sivan.core.tool.ToolSpec;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 速率限制包装 — 按模型限制每分钟调用次数。
 * <p>
 * v1 Model 装饰器，与 RetryableModel / UsageTrackingModel 配合使用。
 */
@Slf4j
public class RateLimitModel implements Model {

    private final Model delegate;
    private final int maxRequestsPerMinute;

    private final AtomicInteger requestCount = new AtomicInteger(0);
    private volatile Instant windowStart = Instant.now();

    public RateLimitModel(Model delegate, int maxRequestsPerMinute) {
        this.delegate = delegate;
        this.maxRequestsPerMinute = maxRequestsPerMinute;
    }

    public RateLimitModel(Model delegate) {
        this(delegate, 30);
    }

    @Override
    public String modelId() { return delegate.modelId(); }

    @Override
    public Mono<Model.ModelResponse> chat(List<Msg> messages, List<ToolSpec> tools, ModelParams params) {
        if (!tryAcquire()) return Mono.error(new RateLimitExceededException(modelId()));
        return delegate.chat(messages, tools, params);
    }

    @Override
    public Flux<ModelChunk> stream(List<Msg> messages, List<ToolSpec> tools, ModelParams params) {
        if (!tryAcquire()) return Flux.error(new RateLimitExceededException(modelId()));
        return delegate.stream(messages, tools, params);
    }

    @Override
    public Mono<List<Float>> embed(String text) {
        return delegate.embed(text);
    }

    private synchronized boolean tryAcquire() {
        Instant now = Instant.now();
        if (Duration.between(windowStart, now).toSeconds() >= 60) {
            windowStart = now;
            requestCount.set(0);
        }
        return requestCount.incrementAndGet() <= maxRequestsPerMinute;
    }

    public static class RateLimitExceededException extends RuntimeException {
        public RateLimitExceededException(String modelId) {
            super("模型 " + modelId + " 每分钟调用次数超过限制");
        }
    }
}
