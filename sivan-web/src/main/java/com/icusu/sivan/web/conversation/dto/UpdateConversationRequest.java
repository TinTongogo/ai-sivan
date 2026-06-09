package com.icusu.sivan.web.conversation.dto;

import lombok.Data;

import java.util.List;
import java.util.UUID;

/**
 * 更新会话请求 DTO。
 */
@Data
public class UpdateConversationRequest {
    private String title;
    private UUID projectId;
    private List<String> knowledgeBaseIds;
    private List<String> mcpServerIds;
}
