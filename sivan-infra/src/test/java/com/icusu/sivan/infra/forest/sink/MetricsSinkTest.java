package com.icusu.sivan.infra.forest.sink;

import com.icusu.sivan.domain.forest.ForestEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link MetricsSink} 单元测试。
 */
class MetricsSinkTest {

    private MetricsSink sink;
    private ForestMetricsCollector collector;
    private int passThrough;

    @BeforeEach
    void setUp() {
        passThrough = 0;
        collector = new ForestMetricsCollector();
        sink = new MetricsSink(event -> passThrough++, collector);
    }

    @Test
    void countsByEventType() {
        sink.emit(ForestEvent.lifecycle("n1", "f1", "a1", ForestEvent.EventType.LIFECYCLE));
        sink.emit(ForestEvent.lifecycle("n2", "f1", "a1", ForestEvent.EventType.LIFECYCLE));
        sink.emit(ForestEvent.error("n3", "f1", "a1", "err"));

        assertEquals(2, collector.count(ForestEvent.EventType.LIFECYCLE));
        assertEquals(1, collector.count(ForestEvent.EventType.ERROR));
    }

    @Test
    void totalCountsAllEvents() {
        sink.emit(ForestEvent.lifecycle("n1", "f1", "a1", ForestEvent.EventType.LIFECYCLE));
        sink.emit(ForestEvent.error("n2", "f1", "a1", "err"));
        sink.emit(ForestEvent.pause("n3", "f1", "a1", "wait"));

        assertEquals(3, (long) collector.snapshot().get("event_lifecycle")
                + (long) collector.snapshot().get("event_error")
                + (long) collector.snapshot().get("event_pause"));
    }

    @Test
    void zeroForUnobservedType() {
        assertEquals(0, collector.count(ForestEvent.EventType.THINKING));
    }

    @Test
    void passesThroughToNext() {
        sink.emit(ForestEvent.lifecycle("n1", "f1", "a1", ForestEvent.EventType.LIFECYCLE));
        assertEquals(1, passThrough);
    }
}
