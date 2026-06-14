package com.icusu.sivan.infra.knowledge.entity;

import com.icusu.sivan.infra.shared.entity.BaseEntity;

import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

/**
 * knowledge_bases 表 JPA 实体，表示知识库元数据。
 */
@Entity
@Table(name = "knowledge_bases", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"kb_name", "account_id"})
})
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class KnowledgeBaseEntity extends BaseEntity {

    @Id
    @Column(name = "kb_name", length = 128)
    private String kbName;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "project_id")
    private UUID projectId;

    @Column(columnDefinition = "TEXT")
    @Builder.Default
    private String description = "";
}
