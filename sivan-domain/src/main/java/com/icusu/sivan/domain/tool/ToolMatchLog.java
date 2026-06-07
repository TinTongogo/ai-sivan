package com.icusu.sivan.domain.tool;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 工具语义匹配记录，记录每次会话中各工具的相似度评分，用于排查工具匹配问题。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolMatchLog {
    private UUID id;
    private UUID accountId;
    private UUID conversationId;
    private String toolName;
    private String serverId;
    private Double similarity;
    private Double threshold;
    private Boolean passed;
    private LocalDateTime createdAt;
}
