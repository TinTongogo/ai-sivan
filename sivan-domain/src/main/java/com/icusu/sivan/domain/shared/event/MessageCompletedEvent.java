package com.icusu.sivan.domain.shared.event;

import java.util.UUID;

/**
 * 消息完成领域事件。当 LLM 流式回复完成时发布。
 */
public record MessageCompletedEvent(
        UUID eventId,
        UUID messageId,
        UUID conversationId,
        UUID accountId,
        UUID projectId,
        String content,
        String thinking,
        String model,
        int totalTokens,
        int durationMs,
        int thinkingDurationMs,
        int thinkingTokens
) implements DomainEvent {

    public MessageCompletedEvent(UUID messageId, UUID conversationId, UUID accountId, UUID projectId,
                                  String content, String thinking, String model,
                                  int totalTokens, int durationMs, int thinkingDurationMs) {
        this(UUID.randomUUID(), messageId, conversationId, accountId, projectId,
                content, thinking, model, totalTokens, durationMs, thinkingDurationMs, 0);
    }

    public MessageCompletedEvent(UUID messageId, UUID conversationId, UUID accountId, UUID projectId,
                                  String content, String thinking, String model,
                                  int totalTokens, int durationMs, int thinkingDurationMs,
                                  int thinkingTokens) {
        this(UUID.randomUUID(), messageId, conversationId, accountId, projectId,
                content, thinking, model, totalTokens, durationMs, thinkingDurationMs, thinkingTokens);
    }
}
