package com.icusu.sivan.infra.forest.sink;

import com.icusu.sivan.domain.forest.ForestEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link NoopSink} 单元测试。
 */
class NoopSinkTest {

    private final NoopSink sink = new NoopSink();

    @Test
    void emitDoesNotThrow() {
        var event = ForestEvent.lifecycle("n1", "f1", "a1", ForestEvent.EventType.NODE_START);
        assertDoesNotThrow(() -> sink.emit(event));
    }

    @Test
    void emitAllTypesDoesNotThrow() {
        for (var type : ForestEvent.EventType.values()) {
            assertDoesNotThrow(() -> sink.emit(
                    new ForestEvent("n1", "f1", "a1", type, "test")));
        }
    }

    @Test
    void emitNullDoesNotThrow() {
        assertDoesNotThrow(() -> sink.emit(null));
    }
}
