package com.icusu.sivan.core.tool;

public record ToolResult(String toolCallId, boolean success, String output) {
    public static ToolResult success(String toolCallId, String output) {
        return new ToolResult(toolCallId, true, output);
    }

    public static ToolResult failure(String toolCallId, String error) {
        return new ToolResult(toolCallId, false, error);
    }
}
