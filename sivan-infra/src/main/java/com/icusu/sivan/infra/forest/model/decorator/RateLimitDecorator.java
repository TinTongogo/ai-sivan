package com.icusu.sivan.infra.forest.model.decorator;

import com.icusu.sivan.domain.forest.event.ChatEvent;
import com.icusu.sivan.domain.shared.port.LanguageModel;
import com.icusu.sivan.domain.forest.vo.ModelCapabilities;
import com.icusu.sivan.domain.forest.vo.ModelParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 速率限制装饰器 — 按模型限制每分钟调用次数。
 * <p>
 * 超过限制时抛 RateLimitExceededException，由 RetryDecorator 或 FallbackDecorator 处理。
 */
public class RateLimitDecorator implements LanguageModel {

    private static final Logger log = LoggerFactory.getLogger(RateLimitDecorator.class);

    private final LanguageModel delegate;
    private final int maxRequestsPerMinute;

    private final AtomicInteger requestCount = new AtomicInteger(0);
    private Instant windowStart = Instant.now();

    public RateLimitDecorator(LanguageModel delegate, int maxRequestsPerMinute) {
        this.delegate = delegate;
        this.maxRequestsPerMinute = maxRequestsPerMinute;
    }

    public RateLimitDecorator(LanguageModel delegate) {
        this(delegate, 30);
    }

    @Override
    public String modelId() { return delegate.modelId(); }

    @Override
    public ModelCapabilities capabilities() { return delegate.capabilities(); }

    @Override
    public Flux<ChatEvent> chat(List<LanguageModel.Msg> messages, ModelParams params) {
        if (!tryAcquire()) {
            return Flux.error(new RateLimitExceededException(modelId(), maxRequestsPerMinute));
        }
        return delegate.chat(messages, params);
    }

    private synchronized boolean tryAcquire() {
        Instant now = Instant.now();
        if (Duration.between(windowStart, now).toSeconds() >= 60) {
            windowStart = now;
            requestCount.set(0);
        }
        int count = requestCount.incrementAndGet();
        if (count > maxRequestsPerMinute) {
            requestCount.decrementAndGet();
            log.warn("[限流] model={} 每分钟 {} 次已达上限", modelId(), maxRequestsPerMinute);
            return false;
        }
        return true;
    }

    public static class RateLimitExceededException extends RuntimeException {
        public RateLimitExceededException(String modelId, int limit) {
            super("模型 " + modelId + " 速率限制: 每分钟 " + limit + " 次");
        }
    }

    @Override
    public String toString() { return "RateLimitDecorator(" + delegate + ")"; }
}
