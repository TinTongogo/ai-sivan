package com.icusu.sivan.infra.forest.sink;

import com.icusu.sivan.domain.forest.context.Delivery;
import com.icusu.sivan.domain.forest.service.EventSink;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link SinkFactory} 单元测试。
 */
class SinkFactoryTest {

    @Test
    void forStreamReturnsDecoratorChain() {
        EventSink terminal = event -> { };
        EventSink chain = SinkFactory.forStream(terminal);
        assertNotNull(chain);
        assertInstanceOf(MetricsSink.class, chain);
    }

    @Test
    void forSummaryReturnsDecoratorChain() {
        EventSink chain = SinkFactory.forSummary();
        assertNotNull(chain);
        assertInstanceOf(MetricsSink.class, chain);
    }

    @Test
    void createWithStreamReturnsDecoratorChain() {
        EventSink terminal = event -> { };
        EventSink chain = SinkFactory.create(Delivery.STREAM, terminal);
        assertInstanceOf(MetricsSink.class, chain);
    }

    @Test
    void createWithSummaryReturnsDecoratorChain() {
        EventSink chain = SinkFactory.create(Delivery.SUMMARY, null);
        assertInstanceOf(MetricsSink.class, chain);
    }

    @Test
    void streamChainDeliversToTerminal() {
        var delivered = new boolean[]{false};
        EventSink terminal = event -> delivered[0] = true;
        EventSink chain = SinkFactory.forStream(terminal);

        chain.emit(ForestEventTestHelper.lifecycleEvent());
        assertTrue(delivered[0]);
    }

    @Test
    void summaryChainDoesNotThrow() {
        EventSink chain = SinkFactory.forSummary();
        assertDoesNotThrow(() -> chain.emit(ForestEventTestHelper.lifecycleEvent()));
    }
}
