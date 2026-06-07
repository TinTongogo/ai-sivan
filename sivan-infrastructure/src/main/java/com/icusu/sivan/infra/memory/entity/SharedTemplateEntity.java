package com.icusu.sivan.infra.memory.entity;

import com.icusu.sivan.infra.shared.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * shared_templates 表 JPA 实体，表示共享的本能模板。
 */
@Entity
@Table(name = "shared_templates", indexes = {
        @Index(name = "idx_shared_owner", columnList = "owner_account_id"),
        @Index(name = "idx_shared_visibility", columnList = "visibility"),
        @Index(name = "idx_shared_pattern", columnList = "pattern_id")
})
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SharedTemplateEntity extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID templateId;

    @Column(name = "pattern_id", nullable = false)
    private UUID patternId;

    @Column(name = "owner_account_id", nullable = false)
    private UUID ownerAccountId;

    @Column(nullable = false, length = 16)
    private String visibility;

    @Column(name = "project_id")
    private UUID projectId;

    @Column(name = "allowed_accounts", columnDefinition = "TEXT")
    private String allowedAccounts;

    @Column(nullable = false, length = 16)
    @Builder.Default
    private String status = "ACTIVE";

    @Column(nullable = false, length = 16)
    @Builder.Default
    private String quality = "NORMAL";

    @Column(name = "use_count", nullable = false)
    @Builder.Default
    private int useCount = 0;

    @Column(name = "success_count", nullable = false)
    @Builder.Default
    private int successCount = 0;

    @Column(name = "shared_at")
    private OffsetDateTime sharedAt;
}
