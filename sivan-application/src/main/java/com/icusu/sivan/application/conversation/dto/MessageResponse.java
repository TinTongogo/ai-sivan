package com.icusu.sivan.application.conversation.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 消息响应 DTO。
 */
@Data
@Builder
@AllArgsConstructor
public class MessageResponse {
    private UUID messageId;
    private UUID conversationId;
    private UUID projectId;
    private String role;
    private String content;
    private String contentType;
    private String thinking;
    private String targetAgent;
    private UUID replyToId;
    /** 被引用消息的角色+内容，batch-fetch 后填充，跨分页也可获取 */
    private Map<String, String> replyTo;
    private Integer sortOrder;
    private String status;
    private String rating;
    private LocalDateTime createdAt;
    private String model;
    private Integer totalTokens;
    private Integer durationMs;
    private Integer thinkingDurationMs;
    private Integer thinkingTokens;
    private String chain;
    private Integer generationIndex;
    private UUID generationGroup;
    private Integer generationTotal;
    private List<String> images;
    private List<String> audios;
    private List<Map<String, Object>> attachments;
    /** 编排阶段详情 */
    private List<Map<String, Object>> sections;
    /** 运行时进度状态 */
    private Map<String, Object> progress;
}
