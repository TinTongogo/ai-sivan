package com.icusu.sivan.domain.orchestration;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 执行产物值对象 — 阶段执行产出的结构化结果。
 */
public class ExecutionArtifact {

    private final UUID artifactId;
    private final UUID executionId;
    private final int phaseIndex;
    private final ArtifactType artifactType;
    private final String name;
    private final String description;
    private final String content;
    private final LocalDateTime createdAt;

    public ExecutionArtifact(UUID executionId, int phaseIndex, String name, String content, ArtifactType type) {
        this.artifactId = UUID.randomUUID();
        this.executionId = executionId;
        this.phaseIndex = phaseIndex;
        this.artifactType = type;
        this.name = name;
        this.description = null;
        this.content = content;
        this.createdAt = LocalDateTime.now();
    }

    public ExecutionArtifact(UUID executionId, int phaseIndex, ArtifactRef ref, String content) {
        this.artifactId = UUID.randomUUID();
        this.executionId = executionId;
        this.phaseIndex = phaseIndex;
        this.artifactType = ref.artifactType();
        this.name = ref.name();
        this.description = ref.description();
        this.content = content;
        this.createdAt = LocalDateTime.now();
    }

    public UUID getArtifactId() { return artifactId; }
    public UUID getExecutionId() { return executionId; }
    public int getPhaseIndex() { return phaseIndex; }
    public ArtifactType getArtifactType() { return artifactType; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getContent() { return content; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
