package com.icusu.sivan.infra.shared.sse;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SseFormatter 编排事件格式化测试。
 */
class SseFormatterTest {

    @Test
    void shouldBuildPhaseStartEvent() {
        String event = SseFormatter.buildPhaseStartEvent("需求分析", "分析师", null, 0, 3);
        assertTrue(event.contains("phase_start"));
        assertTrue(event.contains("需求分析"));
        assertTrue(event.contains("分析师"));
        assertTrue(event.contains("\"phaseIndex\":0"));
        assertTrue(event.contains("\"totalPhases\":3"));
    }

    @Test
    void shouldBuildPhaseStartWithMode() {
        String event = SseFormatter.buildPhaseStartEvent("后端", null, "SEQUENTIAL", 1, 3);
        assertTrue(event.contains("phase_start"));
        assertTrue(event.contains("SEQUENTIAL"));
        assertTrue(event.contains("\"mode\""));
        assertFalse(event.contains("\"agent\""));
    }

    @Test
    void shouldBuildPhaseEndEvent() {
        String event = SseFormatter.buildPhaseEndEvent("编码", "工程师", 1200, 8500, "qwen3-72b");
        assertTrue(event.contains("phase_end"));
        assertTrue(event.contains("编码"));
        assertTrue(event.contains("工程师"));
        assertTrue(event.contains("\"tokens\":1200"));
        assertTrue(event.contains("\"durationMs\":8500"));
        assertTrue(event.contains("qwen3-72b"));
    }

    @Test
    void shouldBuildPhaseEndWithOptionalFields() {
        String event = SseFormatter.buildPhaseEndEvent("测试", null, null, null, null);
        assertTrue(event.contains("phase_end"));
        assertTrue(event.contains("测试"));
        assertFalse(event.contains("\"tokens\""));
        assertFalse(event.contains("\"durationMs\""));
        assertFalse(event.contains("\"model\""));
    }

    @Test
    void shouldBuildProgressEvent() {
        String progressJson = "{\"status\":\"RUNNING\",\"totalPhases\":2}";
        String event = SseFormatter.buildProgressEvent(progressJson);
        assertEquals("{\"type\":\"progress\",\"data\":{\"status\":\"RUNNING\",\"totalPhases\":2}}", event);
    }
}
