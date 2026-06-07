package com.icusu.sivan.core.agent;

public sealed interface AgentEvent permits
        AgentEvent.Thinking, AgentEvent.ToolCall, AgentEvent.ToolResult,
        AgentEvent.Chunk, AgentEvent.Completed, AgentEvent.Error {

    record Thinking(String content) implements AgentEvent {}
    record ToolCall(String id, String name, java.util.Map<String, Object> args) implements AgentEvent {}
    record ToolResult(String id, String name, boolean success, String output) implements AgentEvent {}
    record Chunk(String delta) implements AgentEvent {}
    record Completed(AgentResult result) implements AgentEvent {}
    record Error(Throwable cause) implements AgentEvent {}
}
