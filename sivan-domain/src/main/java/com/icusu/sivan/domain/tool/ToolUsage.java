package com.icusu.sivan.domain.tool;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 工具使用记录。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolUsage {
    private UUID toolUsageId;
    private UUID accountId;
    private String agentName;
    private String toolName;
    private String serverId;
    private boolean success;
    private int durationMs;
    private UUID conversationId;
    private LocalDateTime createdAt;
}
