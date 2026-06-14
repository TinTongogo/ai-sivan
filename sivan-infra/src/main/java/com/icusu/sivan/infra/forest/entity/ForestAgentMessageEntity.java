package com.icusu.sivan.infra.forest.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Entity
@Table(name = "forest_agent_messages")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ForestAgentMessageEntity {

    @Id
    @Column(name = "message_id")
    private UUID messageId;

    @Column(name = "forest_id", nullable = false)
    private UUID forestId;

    @Column(name = "scope_node_id", nullable = false)
    private String scopeNodeId;

    @Column(name = "source_agent", nullable = false, length = 128)
    private String sourceAgent;

    @Column(name = "target_agent", length = 128)
    private String targetAgent;

    @Column(name = "topic", length = 256)
    private String topic;

    @Column(name = "message_type", nullable = false, length = 16)
    private String messageType;

    @Column(name = "payload")
    private String payload;

    @Column(name = "correlation_id")
    private UUID correlationId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (messageId == null) messageId = UUID.randomUUID();
        if (createdAt == null) createdAt = OffsetDateTime.now(ZoneOffset.UTC);
    }
}
