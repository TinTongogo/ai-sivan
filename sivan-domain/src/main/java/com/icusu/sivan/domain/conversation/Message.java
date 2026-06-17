package com.icusu.sivan.domain.conversation;

import com.icusu.sivan.common.enums.MessageStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 对话消息实体。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Message {

    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";

    private UUID messageId;
    private UUID conversationId;
    private UUID accountId;
    private UUID projectId;
    private String role;
    private String content;
    private String contentType;
    /** 'normal' / 'goal_start' / 'goal_end' / 'summary' */
    private String msgType;
    @Builder.Default
    private Double importance = 0.0;
    private String thinking;
    private String targetAgent;
    private UUID replyToId;
    private Integer sortOrder;
    private MessageStatus status;
    private String rating;
    private String model;
    private Integer totalTokens;
    private Integer durationMs;
    private Integer thinkingDurationMs;
    private Integer thinkingTokens;
    /** 图片列表，JSON 数组字符串（data URI 格式） */
    private String images;

    /** 音频列表，JSON 数组字符串（data URI 格式） */
    private String audios;

    /** 非图片文件附件列表，JSON 数组字符串：[{fileId, fileName, mimeType, fileSize}] */
    private String attachments;

    /** 生成序号（同位置从 1 递增） */
    @Builder.Default
    private Integer generationIndex = 1;

    /** 生成组（同一组内不同 generationIndex 表示同一位置的多次生成结果） */
    private UUID generationGroup;

    /** 编排阶段详情 JSONB（V2 编排功能） */
    private String sections;

    /** 运行时进度状态 JSONB（流完成后清除） */
    private String progress;

    private LocalDateTime createdAt;

    public void complete(String content, String thinking, String model, int tokens, int durationMs) {
        this.content = content;
        if (thinking != null && !thinking.isBlank()) this.thinking = thinking;
        this.model = model;
        this.totalTokens = tokens;
        this.durationMs = durationMs;
        this.status = MessageStatus.COMPLETED;
    }

    public void fail(String errorMessage) {
        this.content = errorMessage;
        this.status = MessageStatus.FAILED;
    }

    public boolean isRunning() {
        return this.status == MessageStatus.RUNNING;
    }

    public boolean isAssistant() {
        return ROLE_ASSISTANT.equals(this.role);
    }

    public boolean isUser() {
        return ROLE_USER.equals(this.role);
    }
}
