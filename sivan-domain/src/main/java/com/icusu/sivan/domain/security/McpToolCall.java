package com.icusu.sivan.domain.security;

public record McpToolCall(String serverId, String toolName, String args) implements Action {
    @Override public String type() { return "mcp_tool_call"; }
}
