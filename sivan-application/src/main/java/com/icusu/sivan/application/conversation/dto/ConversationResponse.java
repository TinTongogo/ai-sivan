package com.icusu.sivan.application.conversation.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 会话响应 DTO。
 */
@Data
@Builder
@AllArgsConstructor
public class ConversationResponse {
    private UUID conversationId;
    private UUID projectId;
    private String title;
    private Integer messageCount;
    private List<String> knowledgeBaseIds;
    private List<String> mcpServerIds;
    private LocalDateTime lastMessageAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
