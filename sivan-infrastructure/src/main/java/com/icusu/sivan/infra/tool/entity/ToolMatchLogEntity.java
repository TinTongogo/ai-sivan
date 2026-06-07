package com.icusu.sivan.infra.tool.entity;

import com.icusu.sivan.infra.shared.entity.BaseCreateOnlyEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * tool_match_logs 表 JPA 实体。
 */
@Entity
@Table(name = "tool_match_logs", indexes = {
        @Index(name = "idx_tool_match_logs_conversation", columnList = "conversation_id"),
        @Index(name = "idx_tool_match_logs_account", columnList = "account_id, created_at")
})
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ToolMatchLogEntity extends BaseCreateOnlyEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "conversation_id", nullable = false)
    private UUID conversationId;

    @Column(name = "tool_name", nullable = false, length = 256)
    private String toolName;

    @Column(name = "server_id", length = 128)
    private String serverId;

    @Column
    private Double similarity;

    @Column
    private Double threshold;

    @Column(nullable = false)
    @Builder.Default
    private Boolean passed = false;
}
