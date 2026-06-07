package com.icusu.sivan.infra.memory.entity;

import com.icusu.sivan.infra.shared.entity.BaseCreateOnlyEntity;

import com.icusu.sivan.infra.knowledge.entity.FloatArrayVectorType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnTransformer;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.Type;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * memory_entries 表 JPA 实体，表示长期/短期记忆条目。
 */
@Entity
@Table(name = "memory_entries", indexes = {
        @Index(name = "idx_memory_level", columnList = "account_id, level"),
        @Index(name = "idx_memory_retention", columnList = "retention")
})
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MemoryEntryEntity extends BaseCreateOnlyEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID memoryId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "project_id")
    private UUID projectId;

    @Column(nullable = false, length = 16)
    private String level;

    @Column(name = "scope_id", nullable = false, length = 64)
    private String scopeId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "JSONB")
    private String metadata;

    @Column(columnDefinition = "NUMERIC(5,4)")
    @Builder.Default
    private Float retention = 1.0f;

    @Column(name = "access_count")
    @Builder.Default
    private Integer accessCount = 0;

    @Column(name = "is_archived")
    @Builder.Default
    private Boolean archived = false;

    @Column(name = "is_important")
    @Builder.Default
    private Boolean important = false;

    @Column(columnDefinition = "TEXT")
    private String summary;

    @Column(columnDefinition = "vector(1024)")
    @ColumnTransformer(write = "?::vector")
    @Type(FloatArrayVectorType.class)
    private float[] vector;

    @Column(name = "last_accessed_at")
    private OffsetDateTime lastAccessedAt;
}
