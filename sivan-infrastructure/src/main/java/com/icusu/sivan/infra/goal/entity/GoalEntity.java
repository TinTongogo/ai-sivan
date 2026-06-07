package com.icusu.sivan.infra.goal.entity;

import com.icusu.sivan.infra.shared.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

/**
 * goals 表 JPA 实体。
 */
@Entity
@Table(name = "goals")
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GoalEntity extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID goalId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "project_id")
    private UUID projectId;

    @Column(name = "conversation_id")
    private UUID conversationId;

    @Column(nullable = false, length = 256)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "success_criteria", columnDefinition = "TEXT")
    private String successCriteria;

    @Column(nullable = false, length = 16)
    @Builder.Default
    private String status = "PENDING";

    @Column(name = "auto_mode", length = 24)
    @Builder.Default
    private String autoMode = "AUTO";

    @Column(name = "current_milestone")
    @Builder.Default
    private Integer currentMilestone = 0;

    @Column(name = "total_tasks")
    @Builder.Default
    private Integer totalTasks = 0;

    @Column(name = "completed_tasks")
    @Builder.Default
    private Integer completedTasks = 0;

    @Column(name = "pause_reason", columnDefinition = "TEXT")
    private String pauseReason;

    @Column(name = "file_root_path", length = 1024)
    private String fileRootPath;

    @Column(name = "source_squad_id")
    private UUID sourceSquadId;

    @Column(name = "source_execution_id")
    private UUID sourceExecutionId;

    @Column(name = "squad_topology_json", columnDefinition = "TEXT")
    private String squadTopologyJson;

    @Column(name = "current_phase_index")
    @Builder.Default
    private Integer currentPhaseIndex = 0;

    @Column(name = "completed_at")
    private java.time.OffsetDateTime completedAt;

    @Column(name = "paused_at")
    private java.time.OffsetDateTime pausedAt;

    @Version
    @Column(name = "version")
    @Builder.Default
    private Long version = 0L;
}
