package com.icusu.sivan.infra.agent.entity;

import com.icusu.sivan.infra.shared.entity.BaseEntity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * agents 表 JPA 实体，表示 AI 智能体配置。
 */
@Entity
@Table(name = "agents", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"account_id", "agent_name"})
})
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AgentEntity extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID agentId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "agent_name", nullable = false, length = 64)
    private String agentName;

    @Column(name = "display_name", nullable = false, length = 128)
    private String displayName;

    /** 智能体简介（用于渐进式披露匹配），由 LLM 或用户提供。 */
    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(length = 32)
    private String category;

    @Column(name = "system_prompt", nullable = false, columnDefinition = "TEXT")
    private String systemPrompt;

    @Column(name = "craft_declaration", columnDefinition = "TEXT")
    private String craftDeclaration;

    @Column(name = "skill_ids", columnDefinition = "TEXT")
    private String skillIds;

    @Column(name = "tool_requirements", columnDefinition = "JSONB")
    @JdbcTypeCode(SqlTypes.JSON)
    private String toolRequirements;

    @Column(name = "agent_type", nullable = false, length = 16)
    @Builder.Default
    private String agentType = "USER";

    @Column(length = 16)
    @Builder.Default
    private String status = "ACTIVE";

    /** 业务版本号（手动递增，反映配置变更）。 */
    @Column(nullable = false)
    @Builder.Default
    private Integer version = 1;

    /** JPA 乐观锁（自动递增，与业务版本分离）。 */
    @Version
    @Column(name = "optlock", nullable = false)
    @Builder.Default
    private Integer optlock = 0;

    @Column(name = "created_by", length = 64)
    private String createdBy;

    @Column(name = "usage_count")
    @Builder.Default
    private Integer usageCount = 0;

    @Column(name = "last_used_at")
    private OffsetDateTime lastUsedAt;
}
