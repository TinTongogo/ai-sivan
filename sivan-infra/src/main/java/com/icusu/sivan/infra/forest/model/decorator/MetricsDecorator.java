package com.icusu.sivan.infra.forest.model.decorator;

import com.icusu.sivan.domain.forest.event.ChatEvent;
import com.icusu.sivan.domain.shared.port.LanguageModel;
import com.icusu.sivan.domain.forest.vo.ModelCapabilities;
import com.icusu.sivan.domain.forest.vo.ModelParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 指标装饰器 — 记录调用次数、token 消耗、TTFB 等统计。
 * <p>
 * 进程内内存累计 + INFO 日志输出。
 * Prometheus 集成在 sivan-web 层通过 ManagementServer 暴露。
 */
public class MetricsDecorator implements LanguageModel {

    private static final Logger perfLog = LoggerFactory.getLogger("sivan.perf");

    private final LanguageModel delegate;
    private final AtomicLong invokeCount = new AtomicLong(0);
    private final AtomicLong totalTokens = new AtomicLong(0);

    public MetricsDecorator(LanguageModel delegate) {
        this.delegate = delegate;
    }

    @Override
    public String modelId() { return delegate.modelId(); }

    @Override
    public ModelCapabilities capabilities() { return delegate.capabilities(); }

    @Override
    public Flux<ChatEvent> chat(List<LanguageModel.Msg> messages, ModelParams params) {
        invokeCount.incrementAndGet();
        long t0 = System.nanoTime();
        return delegate.chat(messages, params)
                .doOnNext(event -> {
                    if (event instanceof ChatEvent.Completed completed) {
                        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
                        int tokens = completed.result().usage() != null
                                ? completed.result().usage().totalTokens() : 0;
                        totalTokens.addAndGet(tokens);
                        perfLog.info("[Metrics] model={} invoke={} tokens={} durationMs={}",
                                modelId(), invokeCount.get(), tokens, elapsedMs);
                    }
                });
    }

    public long invokeCount() { return invokeCount.get(); }
    public long totalTokens() { return totalTokens.get(); }

    @Override
    public String toString() { return "MetricsDecorator(" + delegate + ")"; }
}
