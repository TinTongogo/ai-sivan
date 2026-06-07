package com.icusu.sivan.domain.orchestration;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ExecutionArtifactTest {

    @Test
    void shouldCreateArtifactWithNameAndContent() {
        UUID execId = UUID.randomUUID();
        ExecutionArtifact a = new ExecutionArtifact(execId, 0, "计划", "步骤1: 分析",
                ArtifactType.DOC);
        assertEquals(execId, a.getExecutionId());
        assertEquals(0, a.getPhaseIndex());
        assertEquals("计划", a.getName());
        assertEquals("步骤1: 分析", a.getContent());
        assertEquals(ArtifactType.DOC, a.getArtifactType());
        assertNotNull(a.getArtifactId());
        assertNotNull(a.getCreatedAt());
    }

    @Test
    void shouldCreateArtifactFromRef() {
        UUID execId = UUID.randomUUID();
        ArtifactRef ref = new ArtifactRef(UUID.randomUUID().toString(),
                ArtifactType.CODE, "main.java", "入口文件");
        ExecutionArtifact a = new ExecutionArtifact(execId, 2, ref, "public class Main {}");
        assertEquals(execId, a.getExecutionId());
        assertEquals(2, a.getPhaseIndex());
        assertEquals(ArtifactType.CODE, a.getArtifactType());
        assertEquals("main.java", a.getName());
        assertEquals("public class Main {}", a.getContent());
    }

    @Test
    void artifactIdShouldBeRandom() {
        UUID execId = UUID.randomUUID();
        var a1 = new ExecutionArtifact(execId, 0, "a", "content", ArtifactType.DOC);
        var a2 = new ExecutionArtifact(execId, 0, "b", "content", ArtifactType.DATA);
        assertNotEquals(a1.getArtifactId(), a2.getArtifactId());
    }

    @Test
    void artifactTypeShouldMatchInput() {
        UUID execId = UUID.randomUUID();
        for (var type : ArtifactType.values()) {
            var a = new ExecutionArtifact(execId, 0, type.name(), "data", type);
            assertEquals(type, a.getArtifactType());
        }
    }
}
