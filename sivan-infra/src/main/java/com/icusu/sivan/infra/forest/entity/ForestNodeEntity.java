package com.icusu.sivan.infra.forest.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * forest_nodes 表 JPA 实体 — 森林节点（递归树结构）。
 */
@Entity
@Table(name = "forest_nodes")
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ForestNodeEntity implements Persistable<String> {

    @Id
    @Column(name = "node_id", length = 36)
    private String nodeId;

    @Column(name = "forest_id", nullable = false)
    private UUID forestId;

    @Column(name = "node_type", nullable = false, length = 16)
    private String nodeType;

    @Column(name = "parent_node_id", length = 36)
    private String parentNodeId;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;

    @Column(length = 16)
    private String role;          // message: 'user' | 'assistant' | 'system'

    @Column(length = 16)
    private String mode;

    @Column(length = 16)
    private String status;

    @Column(name = "account_id")
    private UUID accountId;

    @Column(name = "project_id")
    private UUID projectId;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Column(columnDefinition = "TEXT")
    private String content;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "JSONB")
    @Builder.Default
    private String metadata = "{}";

    @Column(name = "importance")
    private Double importance;

    @Column(name = "estimate_tokens")
    private Long estimateTokens;

    @Column(name = "kind", length = 16)
    @Builder.Default
    private String kind = "INSTANCE";

    @Column(name = "content_hash", length = 64)
    private String contentHash;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    // ===== 记忆专用列（V30 新增，替代 metadata JSONB 过滤字段） =====

    @Column(length = 16)
    private String level;

    @Column
    private Boolean archived;

    @Column
    private Boolean important;

    @Column(name = "scope_id")
    private UUID scopeId;

    @Column(columnDefinition = "TEXT")
    private String summary;

    @Column(name = "retention")
    private java.math.BigDecimal retention;

    @Column(name = "access_count")
    private Integer accessCount;

    @Column(name = "last_accessed_at")
    private OffsetDateTime lastAccessedAt;

    @Column(name = "vector")
    private float[] vector;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Override
    public String getId() {
        return nodeId;
    }

    @Override
    public boolean isNew() {
        return updatedAt == null;
    }
}
