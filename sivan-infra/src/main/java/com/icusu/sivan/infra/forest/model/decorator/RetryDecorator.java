package com.icusu.sivan.infra.forest.model.decorator;

import com.icusu.sivan.domain.forest.event.ChatEvent;
import com.icusu.sivan.domain.shared.port.LanguageModel;
import com.icusu.sivan.domain.forest.vo.ModelCapabilities;
import com.icusu.sivan.domain.forest.vo.ModelParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.List;

/**
 * 重试装饰器 — 网络错误/限流时自动重试。
 * <p>
 * 使用 Reactor {@link Retry#backoff} 指数退避。
 */
public class RetryDecorator implements LanguageModel {

    private static final Logger log = LoggerFactory.getLogger(RetryDecorator.class);

    private final LanguageModel delegate;
    private final int maxRetries;
    private final Duration minBackoff;

    public RetryDecorator(LanguageModel delegate, int maxRetries, Duration minBackoff) {
        this.delegate = delegate;
        this.maxRetries = maxRetries;
        this.minBackoff = minBackoff;
    }

    public RetryDecorator(LanguageModel delegate) {
        this(delegate, 3, Duration.ofSeconds(1));
    }

    @Override
    public String modelId() { return delegate.modelId(); }

    @Override
    public ModelCapabilities capabilities() { return delegate.capabilities(); }

    @Override
    public Flux<ChatEvent> chat(List<LanguageModel.Msg> messages, ModelParams params) {
        return Flux.defer(() -> delegate.chat(messages, params))
                .retryWhen(Retry.backoff(maxRetries, minBackoff)
                        .filter(throwable -> {
                            // 只重试可恢复错误（网络、限流、5xx）
                            return true;
                        })
                        .doBeforeRetry(signal -> {
                            log.warn("[重试] model={} 第{}次重试, 原因: {}",
                                    modelId(), signal.totalRetries() + 1,
                                    signal.failure().getMessage());
                        })
                        .onRetryExhaustedThrow((spec, signal) -> signal.failure()));
    }

    @Override
    public String toString() { return "RetryDecorator(" + delegate + ")"; }
}
