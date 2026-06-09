package com.icusu.sivan.domain.forest.service;

import java.util.List;

/**
 * 非流式聊天结果。
 */
public record ChatResult(
        String text,
        String thinking,
        List<ChatEvent.ToolCall> toolCalls,
        TokenUsage usage,
        String modelId,
        long durationMs
) {
    public static ChatResult empty() {
        return new ChatResult("", null, List.of(), TokenUsage.ZERO, "", 0);
    }
}
