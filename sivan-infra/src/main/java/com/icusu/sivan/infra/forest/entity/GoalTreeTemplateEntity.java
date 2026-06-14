package com.icusu.sivan.infra.forest.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * goal_tree_templates 表 JPA 实体。
 */
@Entity
@Table(name = "goal_tree_templates")
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GoalTreeTemplateEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "template_id")
    private UUID templateId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "root_json", nullable = false, columnDefinition = "JSONB")
    private String rootJson;

    @Column(name = "usage_count")
    @Builder.Default
    private int usageCount = 0;

    @Column(name = "success_count")
    @Builder.Default
    private int successCount = 0;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
