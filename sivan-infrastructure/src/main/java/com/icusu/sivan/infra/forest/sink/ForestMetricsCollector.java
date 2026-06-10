package com.icusu.sivan.infra.forest.sink;

import com.icusu.sivan.domain.forest.ForestEvent;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 全局森林指标收集器 — 所有 MetricsSink 写入同一实例。
 * <p>
 * 提供实时事件计数、token 用量汇总等运营数据。
 */
@Component
public class ForestMetricsCollector {

    private final ConcurrentHashMap<ForestEvent.EventType, AtomicLong> eventCounters = new ConcurrentHashMap<>();
    private final AtomicLong totalTokens = new AtomicLong(0);
    private final AtomicLong totalDurationMs = new AtomicLong(0);
    private final AtomicLong executionCount = new AtomicLong(0);

    public void recordEvent(ForestEvent.EventType type) {
        eventCounters.computeIfAbsent(type, t -> new AtomicLong()).incrementAndGet();
    }

    public void recordExecution(int tokens, int durationMs) {
        totalTokens.addAndGet(tokens);
        totalDurationMs.addAndGet(durationMs);
        executionCount.incrementAndGet();
    }

    public long count(ForestEvent.EventType type) {
        var c = eventCounters.get(type);
        return c != null ? c.get() : 0L;
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> snap = new ConcurrentHashMap<>();
        for (var entry : eventCounters.entrySet()) {
            snap.put("event_" + entry.getKey().name().toLowerCase(), entry.getValue().get());
        }
        snap.put("total_tokens", totalTokens.get());
        snap.put("total_duration_ms", totalDurationMs.get());
        snap.put("execution_count", executionCount.get());
        if (executionCount.get() > 0) {
            snap.put("avg_tokens_per_exec", totalTokens.get() / executionCount.get());
            snap.put("avg_duration_ms_per_exec", totalDurationMs.get() / executionCount.get());
        }
        return snap;
    }
}
