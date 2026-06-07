package com.icusu.sivan.infra.goal.entity;

import com.icusu.sivan.infra.shared.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

/**
 * goal_milestones 表 JPA 实体。
 */
@Entity
@Table(name = "goal_milestones")
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GoalMilestoneEntity extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID milestoneId;

    @Column(name = "goal_id", nullable = false)
    private UUID goalId;

    @Column(nullable = false, length = 256)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;

    @Column(name = "phase_index")
    @Builder.Default
    private Integer phaseIndex = 0;

    @Column(name = "phase_mode", length = 32)
    private String phaseMode;
}
