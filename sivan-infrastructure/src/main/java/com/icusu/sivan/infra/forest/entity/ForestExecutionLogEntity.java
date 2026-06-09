package com.icusu.sivan.infra.forest.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.domain.Persistable;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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

    @Column(name = "message")
    private String message;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (logId == null) logId = UUID.randomUUID();
        if (createdAt == null) createdAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    @Override
    public UUID getId() { return logId; }

    @Override
    public boolean isNew() { return logId == null; }
}
