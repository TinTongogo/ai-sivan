package com.icusu.sivan.infra.goal.entity;

import com.icusu.sivan.infra.shared.entity.BaseCreateOnlyEntity;
import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

/**
 * goal_artifacts 表 JPA 实体。
 */
@Entity
@Table(name = "goal_artifacts")
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GoalArtifactEntity extends BaseCreateOnlyEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID artifactId;

    @Column(name = "goal_id", nullable = false)
    private UUID goalId;

    @Column(name = "milestone_order", nullable = false)
    private Integer milestoneOrder;

    @Column(name = "task_order", nullable = false)
    private Integer taskOrder;

    @Column(name = "file_path", nullable = false, length = 1024)
    private String filePath;

    @Column(name = "file_type", length = 32)
    private String fileType;

    @Column(columnDefinition = "TEXT")
    private String summary;

    @Column(name = "file_size")
    @Builder.Default
    private Long fileSize = 0L;
}
