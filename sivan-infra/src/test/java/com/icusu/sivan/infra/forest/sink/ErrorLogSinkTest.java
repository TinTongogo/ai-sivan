package com.icusu.sivan.infra.forest.sink;

import com.icusu.sivan.domain.forest.ForestEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ErrorLogSink} 单元测试。
 */
class ErrorLogSinkTest {

    private int passThrough;
    private ErrorLogSink sink;

    @BeforeEach
    void setUp() {
        passThrough = 0;
        sink = new ErrorLogSink(event -> passThrough++);
    }

    @Test
    void errorEventsPassThrough() {
        sink.emit(ForestEvent.error("n1", "f1", "a1", "测试错误"));
        assertEquals(1, passThrough);
    }

    @Test
    void pauseEventsPassThrough() {
        sink.emit(ForestEvent.pause("n2", "f1", "a1", "等待审批"));
        assertEquals(1, passThrough);
    }

    @Test
    void lifecycleEventsPassThrough() {
        sink.emit(ForestEvent.lifecycle("n3", "f1", "a1", ForestEvent.EventType.NODE_START));
        assertEquals(1, passThrough);
    }

    @Test
    void allEventsPassThrough() {
        for (var type : ForestEvent.EventType.values()) {
            sink.emit(new ForestEvent("n", "f", "a", type, "test"));
        }
        assertEquals(ForestEvent.EventType.values().length, passThrough);
    }
}
