package com.icusu.sivan.agent.tool;

import com.icusu.sivan.core.tool.ToolProvider;
import com.icusu.sivan.core.tool.ToolRegistry;
import com.icusu.sivan.core.tool.ToolResult;
import com.icusu.sivan.core.tool.ToolSpec;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * {@link ToolProvider} 实现 — 将 {@link ToolRegistry} 适配为 Agent 可用的工具提供者。
 * <p>
 * 供 {@link com.icusu.sivan.core.agent.Agent.Builder#toolProvider(ToolProvider)}
 * 在构建 Agent 时传入，使 {@link com.icusu.sivan.agent.strategy.ReActExecutionStrategy}
 * 能通过统一的 {@link ToolProvider} 接口执行工具调用。
 */
public class AgentToolProvider implements ToolProvider {

    private final ToolRegistry registry;
    private final List<ToolSpec> tools;

    public AgentToolProvider(ToolRegistry registry, List<ToolSpec> tools) {
        this.registry = registry;
        this.tools = tools;
    }

    @Override
    public String providerId() {
        return "agent";
    }

    @Override
    public List<ToolSpec> listTools() {
        return tools;
    }

    @Override
    public Mono<ToolResult> execute(String toolName, Map<String, Object> args) {
        var executor = registry.find(toolName);
        if (executor == null) {
            return Mono.just(ToolResult.failure(toolName, "工具未注册: " + toolName));
        }
        var call = new com.icusu.sivan.core.message.Content.ToolCall(
                "agent-" + toolName, toolName, args != null ? args : Map.of());
        var ctx = com.icusu.sivan.core.context.ExecutionContext.create(null, List.of(), Map.of());
        return executor.execute(call, ctx)
                .onErrorResume(e -> Mono.just(ToolResult.failure(toolName, e.getMessage())));
    }
}
