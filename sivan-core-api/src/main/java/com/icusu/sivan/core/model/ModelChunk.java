package com.icusu.sivan.core.model;

import java.util.List;

public record ModelChunk(String content, String thinking, String finishReason, TokenUsage usage,
                         List<ToolCallDelta> toolCallDeltas) {

    public record ToolCallDelta(int index, String id, String name, String arguments) {}

    public ModelChunk(String content, String thinking, String finishReason, TokenUsage usage,
                      List<ToolCallDelta> toolCallDeltas) {
        this.content = content != null ? content : "";
        this.thinking = thinking != null ? thinking : "";
        this.finishReason = finishReason;
        this.usage = usage;
        this.toolCallDeltas = toolCallDeltas != null ? List.copyOf(toolCallDeltas) : List.of();
    }

    public ModelChunk(String content) {
        this(content, "", null, null, List.of());
    }

    public ModelChunk(String content, String finishReason, TokenUsage usage) {
        this(content, "", finishReason, usage, List.of());
    }

    public ModelChunk(String content, String thinking, String finishReason, TokenUsage usage) {
        this(content, thinking, finishReason, usage, List.of());
    }

    public boolean isToolCall() {
        return "tool_calls".equals(finishReason);
    }
}
