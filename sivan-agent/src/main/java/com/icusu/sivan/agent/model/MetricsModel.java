package com.icusu.sivan.agent.model;

import com.icusu.sivan.core.message.Msg;
import com.icusu.sivan.core.model.Model;

import com.icusu.sivan.core.model.ModelChunk;
import com.icusu.sivan.core.tool.ToolSpec;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 指标采集包装 — 记录调用次数、耗时和 token 消耗。
 * <p>
 * v1 Model 装饰器，统计数据通过 INFO 日志输出。
 */
@Slf4j
public class MetricsModel implements Model {

    private final Model delegate;
    private final AtomicLong invokeCount = new AtomicLong(0);
    private final AtomicLong totalTokens = new AtomicLong(0);

    public MetricsModel(Model delegate) {
        this.delegate = delegate;
    }

    @Override
    public String modelId() { return delegate.modelId(); }

    @Override
    public Mono<Model.ModelResponse> chat(List<Msg> messages, List<ToolSpec> tools, ModelParams params) {
        long t0 = System.nanoTime();
        invokeCount.incrementAndGet();
        return delegate.chat(messages, tools, params)
                .doOnNext(resp -> {
                    long elapsed = (System.nanoTime() - t0) / 1_000_000;
                    int tokens = resp.usage() != null ? resp.usage().totalTokens() : 0;
                    totalTokens.addAndGet(tokens);
                    log.info("[Metrics] model={} invoke={} tokens={} durationMs={}",
                            modelId(), invokeCount.get(), tokens, elapsed);
                });
    }

    @Override
    public Flux<ModelChunk> stream(List<Msg> messages, List<ToolSpec> tools, ModelParams params) {
        long t0 = System.nanoTime();
        invokeCount.incrementAndGet();
        return delegate.stream(messages, tools, params)
                .doOnComplete(() -> {
                    long elapsed = (System.nanoTime() - t0) / 1_000_000;
                    log.info("[Metrics] model={} invoke={} durationMs={}",
                            modelId(), invokeCount.get(), elapsed);
                })
                .doOnNext(chunk -> {
                    if (chunk.usage() != null) {
                        totalTokens.addAndGet(chunk.usage().totalTokens());
                    }
                });
    }

    @Override
    public Mono<List<Float>> embed(String text) {
        invokeCount.incrementAndGet();
        return delegate.embed(text);
    }

    public long invokeCount() { return invokeCount.get(); }
    public long totalTokens() { return totalTokens.get(); }
}
