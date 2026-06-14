package com.icusu.sivan.infra.forest.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "forest_execution_logs")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ForestExecutionLogEntity implements Persistable<UUID> {

    @Id
    @Column(name = "log_id")
    private UUID logId;

    @Column(name = "forest_id", nullable = false)
    private UUID forestId;

    @Column(name = "node_id", nullable = false)
    private String nodeId;

    @Column(name = "event_type", nullable = false, length = 16)
    private String eventType;

    @Column(name = "status_before", length = 16)
    private String statusBefore;

    @Column(name = "status_after", length = 16)
    private String statusAfter;

    @Column(name = "message")
    private String message;

    /** 元数据，以 JSONB 存储。未设置时 @PrePersist 初始化为空 Map。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb")
    @Builder.Default
    private Map<String, Object> metadata = null;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (logId == null) logId = UUID.randomUUID();
        if (createdAt == null) createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        if (metadata == null) metadata = new java.util.HashMap<>();
    }

    @Override
    public UUID getId() { return logId; }

    @Override
    public boolean isNew() { return logId == null; }
}
