package com.icusu.sivan.domain.orchestration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ArtifactRefTest {

    @Test
    void shouldCreateValidRef() {
        var ref = new ArtifactRef("id-1", ArtifactType.CODE, "test.java", "测试文件");
        assertEquals("id-1", ref.artifactId());
        assertEquals(ArtifactType.CODE, ref.artifactType());
        assertEquals("test.java", ref.name());
        assertEquals("测试文件", ref.description());
    }

    @Test
    void shouldThrowWhenArtifactIdIsBlank() {
        assertThrows(IllegalArgumentException.class, () ->
                new ArtifactRef("", ArtifactType.CODE, "test", "desc"));
    }

    @Test
    void shouldThrowWhenArtifactTypeIsNull() {
        assertThrows(IllegalArgumentException.class, () ->
                new ArtifactRef("id", null, "test", "desc"));
    }
}
