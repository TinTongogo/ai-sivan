package com.icusu.sivan.domain.forest.vo;

import com.icusu.sivan.domain.forest.event.ChatEvent;
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
