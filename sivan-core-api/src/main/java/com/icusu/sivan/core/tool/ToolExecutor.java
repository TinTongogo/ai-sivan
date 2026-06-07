package com.icusu.sivan.core.tool;

import com.icusu.sivan.core.context.ExecutionContext;
import com.icusu.sivan.core.message.Content;
import reactor.core.publisher.Mono;

/**
 * 工具执行器端口：执行一次工具调用。
 */
@FunctionalInterface
public interface ToolExecutor {

    Mono<ToolResult> execute(Content.ToolCall call, ExecutionContext ctx);
}
