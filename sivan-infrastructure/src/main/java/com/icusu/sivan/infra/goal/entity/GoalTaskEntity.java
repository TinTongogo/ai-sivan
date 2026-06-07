package com.icusu.sivan.infra.goal.entity;

import com.icusu.sivan.infra.shared.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

/**
 * goal_tasks 表 JPA 实体。
 */
@Entity
@Table(name = "goal_tasks")
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GoalTaskEntity extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID taskId;

    @Column(name = "goal_id", nullable = false)
    private UUID goalId;

    @Column(name = "milestone_id")
    private UUID milestoneId;

    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String description;

    @Column(nullable = false)
    @Builder.Default
    private Boolean completed = false;

    @Column(length = 16)
    private String status;

    @Column(name = "artifact_summary", columnDefinition = "TEXT")
    private String artifactSummary;

    @Column(name = "input_artifact", columnDefinition = "TEXT")
    private String inputArtifact;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "output_files", columnDefinition = "JSONB")
    @Builder.Default
    private String outputFiles = "[]";

    @Column(name = "task_ref", length = 64)
    private String taskRef;

    @Column(name = "agent_index")
    @Builder.Default
    private Integer agentIndex = 0;

    @Column(name = "agent_name", length = 128)
    private String agentName;
}
