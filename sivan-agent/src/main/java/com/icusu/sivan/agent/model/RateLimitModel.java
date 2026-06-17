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
 * 速率限制包装 — 按模型限制每分钟调用次数，超限时等待而非报错。
 * <p>
 * 使用滑动时间窗口 + 等待队列，不阻塞线程。
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
        this(delegate, 60);
    }

    @Override
    public String modelId() { return delegate.modelId(); }

    @Override
    public Mono<Model.ModelResponse> chat(List<Msg> messages, List<ToolSpec> tools, ModelParams params) {
        return waitForSlot().then(delegate.chat(messages, tools, params));
    }

    @Override
    public Flux<ModelChunk> stream(List<Msg> messages, List<ToolSpec> tools, ModelParams params) {
        return waitForSlot().thenMany(delegate.stream(messages, tools, params));
    }

    @Override
    public Mono<List<Float>> embed(String text) {
        return delegate.embed(text);
    }

    /**
     * 获取调用配额。达到上限时等待下个时间窗口腾出位置。
     * 每秒检查一次，非阻塞。
     */
    private Mono<Void> waitForSlot() {
        return Mono.defer(() -> {
            if (tryAcquire()) return Mono.empty();
            // 计算到下一个 60 秒窗口重置的等待时间
            long waitMs = Duration.between(Instant.now(), windowStart.plusSeconds(60)).toMillis();
            if (waitMs <= 0) return Mono.empty();
            long pollMs = Math.min(waitMs, 1000);
            log.debug("[限流] {} 配额已满，等待 {}ms", delegate.modelId(), pollMs);
            return Mono.delay(Duration.ofMillis(pollMs))
                    .then(Mono.defer(this::waitForSlot));
        });
    }

    /**
     * 尝试获取一个调用配额。
     * 每分钟窗口重置一次配额计数器。
     */
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
