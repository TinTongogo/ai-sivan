package com.icusu.sivan.infra.pipeline.entity;

import com.icusu.sivan.infra.shared.entity.BaseCreateOnlyEntity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * pipeline_steps 表 JPA 实体 — 编排流水线步骤明细。
 */
@Entity
@Table(name = "pipeline_steps", indexes = {
        @Index(name = "idx_pipeline_steps_message", columnList = "message_id, sequence"),
        @Index(name = "idx_pipeline_steps_parent", columnList = "parent_step_id"),
        @Index(name = "idx_pipeline_steps_execution", columnList = "execution_id")
})
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PipelineStepEntity extends BaseCreateOnlyEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID stepId;

    @Column(name = "message_id", nullable = false)
    private UUID messageId;

    @Column(name = "routing_decision_id")
    private UUID routingDecisionId;

    @Column(name = "execution_id")
    private UUID executionId;

    @Column(name = "step_type", nullable = false, length = 32)
    private String stepType;

    @Column(name = "step_name", length = 128)
    private String stepName;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(nullable = false)
    private Integer sequence;

    @Column(name = "parent_step_id")
    private UUID parentStepId;

    @Column(name = "started_at")
    private OffsetDateTime startedAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @Column(name = "duration_ms")
    private Integer durationMs;

    @Column(name = "input_summary", columnDefinition = "TEXT")
    private String inputSummary;

    @Column(name = "output_summary", columnDefinition = "TEXT")
    private String outputSummary;

    @Column(name = "agent_name", length = 64)
    private String agentName;

    @Column(name = "model_name", length = 64)
    private String modelName;

    @Column(name = "token_count")
    private Integer tokenCount;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata_json", columnDefinition = "JSONB")
    private String metadataJson;
}
