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
 * tool_usage 表 JPA 实体，表示工具调用记录。
 */
@Entity
@Table(name = "tool_usage", indexes = {
        @Index(name = "idx_tool_usage_account", columnList = "account_id, tool_name"),
        @Index(name = "idx_tool_usage_agent", columnList = "account_id, agent_name, tool_name"),
        @Index(name = "idx_tool_usage_created", columnList = "account_id, created_at")
})
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ToolUsageEntity extends BaseCreateOnlyEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID toolUsageId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "agent_name", length = 64)
    private String agentName;

    @Column(name = "tool_name", nullable = false, length = 128)
    private String toolName;

    @Column(name = "server_id", length = 128)
    @Builder.Default
    private String serverId = "";

    @Column(nullable = false)
    @Builder.Default
    private Boolean success = true;

    @Column(name = "duration_ms", nullable = false)
    @Builder.Default
    private Integer durationMs = 0;

    @Column(name = "conversation_id")
    private UUID conversationId;
}
