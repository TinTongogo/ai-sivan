package com.icusu.sivan.infra.forest.sink;

import com.icusu.sivan.domain.forest.ForestEvent;
import com.icusu.sivan.domain.forest.service.EventSink;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 指标 Sink — 装饰器，记录事件计数。
 * <p>
 * 按 {@link ForestEvent.EventType} 统计发射次数，可用于监控或仪表盘。
 */
public class MetricsSink implements EventSink {

    private final EventSink next;
    private final ConcurrentHashMap<ForestEvent.EventType, AtomicLong> counters = new ConcurrentHashMap<>();

    public MetricsSink(EventSink next) {
        this.next = next;
    }

    @Override
    public void emit(ForestEvent event) {
        counters.computeIfAbsent(event.type(), t -> new AtomicLong()).incrementAndGet();
        next.emit(event);
    }

    /** 获取指定类型的事件计数。 */
    public long count(ForestEvent.EventType type) {
        var c = counters.get(type);
        return c != null ? c.get() : 0L;
    }

    /** 获取所有类型的累计总数。 */
    public long total() {
        return counters.values().stream().mapToLong(AtomicLong::get).sum();
    }
}
