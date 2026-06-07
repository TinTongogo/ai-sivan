package com.icusu.sivan.infra.orchestration.entity;

import com.icusu.sivan.infra.shared.entity.BaseCreateOnlyEntity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * squad_executions 表 JPA 实体，表示 Squad 执行记录。
 */
@Entity
@Table(name = "squad_executions", indexes = {
        @Index(name = "idx_execution_status", columnList = "account_id, status")
})
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SquadExecutionEntity extends BaseCreateOnlyEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID executionId;

    @Column(name = "squad_id")
    private UUID squadId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "project_id")
    private UUID projectId;

    @Column(name = "task_description", columnDefinition = "TEXT")
    private String taskDescription;

    @Column(length = 16)
    @Builder.Default
    private String status = "PENDING";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "topology_snapshot", columnDefinition = "JSONB")
    private String topologySnapshot;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "context_json", columnDefinition = "JSONB")
    private String contextJson;

    @Column(name = "current_phase")
    private Integer currentPhase;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "agent_state", columnDefinition = "JSONB")
    private String agentState;

    @Column(columnDefinition = "TEXT")
    private String content;

    @Column(columnDefinition = "TEXT")
    private String thinking;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "started_at")
    private OffsetDateTime startedAt;

    @Column(name = "paused_at")
    private OffsetDateTime pausedAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;
}
