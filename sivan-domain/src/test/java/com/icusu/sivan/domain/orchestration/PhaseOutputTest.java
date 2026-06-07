package com.icusu.sivan.domain.orchestration;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PhaseOutputTest {

    @Test
    void shouldCreateWithOnlyContent() {
        var output = new PhaseOutput("测试内容");
        assertEquals("测试内容", output.content());
        assertTrue(output.artifacts().isEmpty());
        assertTrue(output.summary().isEmpty());
        assertTrue(output.agents().isEmpty());
        assertTrue(output.artifactRefs().isEmpty());
        assertNull(output.confidence());
    }

    @Test
    void shouldCreateWithAllFields() {
        var output = new PhaseOutput(
                "正文内容",
                Map.of("key1", "value1"),
                "摘要",
                List.of("agent1"),
                List.of(new ArtifactRef("id1", ArtifactType.CODE, "test.java", "测试文件")),
                0.95
        );
        assertEquals("正文内容", output.content());
        assertEquals("value1", output.artifacts().get("key1"));
        assertEquals("摘要", output.summary());
        assertEquals(1, output.agents().size());
        assertEquals(1, output.artifactRefs().size());
        assertEquals(0.95, output.confidence(), 0.01);
    }

    @Test
    void shouldHandleNullFieldsGracefully() {
        var output = new PhaseOutput("内容", null, null, null, null, null);
        assertNotNull(output.artifacts());
        assertNotNull(output.summary());
        assertNotNull(output.agents());
        assertNotNull(output.artifactRefs());
    }
}
