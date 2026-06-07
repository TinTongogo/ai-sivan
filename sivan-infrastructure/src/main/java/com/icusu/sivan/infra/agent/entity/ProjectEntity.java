package com.icusu.sivan.infra.agent.entity;

import com.icusu.sivan.infra.shared.entity.BaseEntity;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * projects 表 JPA 实体，表示用户项目。
 */
@Entity
@Table(name = "projects")
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProjectEntity extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID projectId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "sort_order")
    @Builder.Default
    private Integer sortOrder = 0;

    @Column(name = "local_path", length = 1024)
    private String localPath;

    @Column(name = "undeletable")
    @Builder.Default
    private Boolean undeletable = false;

    @Column(name = "archived")
    @Builder.Default
    private Boolean archived = false;

    @Column(name = "archived_at")
    private OffsetDateTime archivedAt;

    @Column(name = "local_path_auto")
    @Builder.Default
    private Boolean localPathAuto = false;

    @Column(name = "short_id", length = 32)
    private String shortId;
}
