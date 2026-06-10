package com.icusu.sivan.infra.forest.sink;

import com.icusu.sivan.domain.forest.ForestEvent;
import com.icusu.sivan.domain.forest.service.EventSink;

/**
 * 指标 Sink — 装饰器，记录事件计数到 {@link ForestMetricsCollector}。
 */
public class MetricsSink implements EventSink {

    private final EventSink next;
    private final ForestMetricsCollector collector;

    public MetricsSink(EventSink next, ForestMetricsCollector collector) {
        this.next = next;
        this.collector = collector;
    }

    @Override
    public void emit(ForestEvent event) {
        collector.recordEvent(event.type());
        next.emit(event);
    }
}
