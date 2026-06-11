package com.icusu.sivan.infra.forest.sink;

import com.icusu.sivan.domain.forest.context.Delivery;
import com.icusu.sivan.domain.forest.service.EventSink;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link SinkFactory} 单元测试。
 */
class SinkFactoryTest {

    private static final ForestMetricsCollector metrics = new ForestMetricsCollector();

    @Test
    void forStreamReturnsDecoratorChain() {
        EventSink terminal = event -> { };
        EventSink chain = SinkFactory.forStream(terminal, null, metrics);
        assertNotNull(chain);
        assertInstanceOf(MetricsSink.class, chain);
    }

    @Test
    void forSummaryReturnsDecoratorChain() {
        EventSink chain = SinkFactory.forSummary(null, metrics);
        assertNotNull(chain);
        assertInstanceOf(MetricsSink.class, chain);
    }

    @Test
    void createWithStreamReturnsDecoratorChain() {
        EventSink terminal = event -> { };
        EventSink chain = SinkFactory.create(Delivery.STREAM, terminal, null, metrics);
        assertInstanceOf(MetricsSink.class, chain);
    }

    @Test
    void createWithSummaryReturnsDecoratorChain() {
        EventSink chain = SinkFactory.create(Delivery.SUMMARY, null, null, metrics);
        assertInstanceOf(MetricsSink.class, chain);
    }

    @Test
    void streamChainDeliversToTerminal() {
        var delivered = new boolean[]{false};
        EventSink terminal = event -> delivered[0] = true;
        EventSink chain = SinkFactory.forStream(terminal, null, metrics);

        chain.emit(ForestEventTestHelper.lifecycleEvent());
        assertTrue(delivered[0]);
    }

    @Test
    void summaryChainDoesNotThrow() {
        EventSink chain = SinkFactory.forSummary(null, metrics);
        assertDoesNotThrow(() -> chain.emit(ForestEventTestHelper.lifecycleEvent()));
    }
}
