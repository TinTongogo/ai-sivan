package com.icusu.sivan.infra.execution.entity;

import com.icusu.sivan.infra.shared.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

/**
 * execution_artifacts 表 JPA 实体，存储阶段产出的结构化产物。
 */
@Entity
@Table(name = "execution_artifacts", indexes = {
        @Index(name = "idx_artifact_execution", columnList = "execution_id")
})
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExecutionArtifactEntity extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID artifactId;

    @Column(name = "execution_id", nullable = false)
    private UUID executionId;

    @Column(name = "phase_index", nullable = false)
    private int phaseIndex;

    @Column(name = "artifact_type", nullable = false, length = 16)
    private String artifactType;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;
}
