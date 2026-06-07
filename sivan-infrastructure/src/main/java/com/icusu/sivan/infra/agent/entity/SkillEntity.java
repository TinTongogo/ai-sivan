package com.icusu.sivan.infra.agent.entity;

import com.icusu.sivan.infra.shared.entity.BaseEntity;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * skills 表 JPA 实体，表示智能体技能定义。
 */
@Entity
@Table(name = "skills", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"account_id", "skill_code"})
})
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SkillEntity extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID skillId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "project_id")
    private UUID projectId;

    @Column(name = "skill_code", nullable = false, length = 64)
    private String skillCode;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(name = "display_name", length = 128)
    private String displayName;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(columnDefinition = "TEXT")
    private String content;

    @Column(length = 64)
    private String category;

    @Column(columnDefinition = "TEXT")
    private String tags;

    @Column(name = "usage_count")
    @Builder.Default
    private Integer usageCount = 0;

    @Column(name = "last_used_at")
    private OffsetDateTime lastUsedAt;

    @Column(length = 16)
    @Builder.Default
    private String status = "ACTIVE";

    @Column(name = "skill_type", length = 16)
    @Builder.Default
    private String skillType = "USER";
}
