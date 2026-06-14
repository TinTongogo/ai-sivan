package com.icusu.sivan.application.conversation.dto;

import lombok.Data;

import java.util.UUID;

/**
 * 创建会话请求 DTO。
 */
@Data
public class CreateConversationRequest {
    private String title;
    private UUID projectId;
}
