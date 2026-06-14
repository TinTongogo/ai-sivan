package com.icusu.sivan.domain.forest.event;

import com.icusu.sivan.domain.forest.vo.ChatResult;

/**
 * 聊天事件 — 统一的流式事件协议。
 * <p>
 * 所有模型（OpenAI / Anthropic / Ollama）的事件都转换为此 sealed 类型。
 */
public sealed interface ChatEvent {

    /**
     * IO 已开始（可用于计算 TTFB）。
     */
    record Started() implements ChatEvent {}

    /**
     * 正常 token 输出。
     */
    record Chunk(String text) implements ChatEvent {}

    /**
     * 推理过程 token（仅 reasoning 模型）。
     */
    record Thinking(String text) implements ChatEvent {}

    /**
     * 模型请求调用工具。
     */
    record ToolCall(String id, String name, String args) implements ChatEvent {}

    /**
     * 工具调用结果已交回，模型继续生成。
     */
    record ToolResult(String id) implements ChatEvent {}

    /**
     * 调用结束。
     */
    record Completed(ChatResult result) implements ChatEvent {}

    /**
     * 调用异常。
     */
    record Error(Throwable cause) implements ChatEvent {}
}
