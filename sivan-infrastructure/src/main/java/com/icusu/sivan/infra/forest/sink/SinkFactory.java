package com.icusu.sivan.infra.forest.sink;

import com.icusu.sivan.domain.forest.context.Delivery;
import com.icusu.sivan.domain.forest.service.EventSink;
import com.icusu.sivan.infra.forest.repository.ForestExecutionLogJpaRepository;

/**
 * Sink 工厂 — 按传递模式创建 EventSink 装饰器链。
 * <p>
 * 工厂方法：
 * <ul>
 *   <li>{@link #forSummary(ForestExecutionLogJpaRepository, ForestMetricsCollector)} → {@code MetricsSink
 *   (ErrorLogSink(ExecutionLogSink(NoopSink)))}</li>
 * </ul>
 */
public final class SinkFactory {

    private SinkFactory() { }

    public static EventSink forStream(EventSink terminal, ForestExecutionLogJpaRepository logRepo,
                                       ForestMetricsCollector metrics) {
        return new MetricsSink(new ErrorLogSink(new ExecutionLogSink(terminal, logRepo)), metrics);
    }

    public static EventSink forSummary(ForestExecutionLogJpaRepository logRepo,
                                       ForestMetricsCollector metrics) {
        return new MetricsSink(new ErrorLogSink(new ExecutionLogSink(new NoopSink(), logRepo)), metrics);
    }

    public static EventSink create(Delivery delivery, EventSink terminal,
                                   ForestExecutionLogJpaRepository logRepo,
                                   ForestMetricsCollector metrics) {
        return switch (delivery) {
            case STREAM -> forStream(terminal, logRepo, metrics);
            case SUMMARY -> forSummary(logRepo, metrics);
        };
    }
}
