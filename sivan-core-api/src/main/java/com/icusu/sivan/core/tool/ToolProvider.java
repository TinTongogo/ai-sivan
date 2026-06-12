package com.icusu.sivan.core.tool;

import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * 工具提供者 — 每个 MCP 服务器或内部工具集一个实现。
 * <p>
 * 扩展方法（4.5 节）：
 * <ul>
 *   <li>{@link #isHealthy()} — 当前是否健康可用</li>
 *   <li>{@link #healthMessage()} — 不可用时的原因</li>
 *   <li>{@link #listToolsAsync()} — 异步获取工具列表（可包含健康检查）</li>
 * </ul>
 */
public interface ToolProvider {

    /** 提供者标识。MCP 服务器 ID 或 "internal"。 */
    String providerId();

    /** 本提供者支持的所有工具列表。 */
    List<ToolSpec> listTools();

    /** 调用一个工具。 */
    Mono<ToolResult> execute(String toolName, Map<String, Object> args);

    /** 当前是否健康可用。 */
    default boolean isHealthy() { return true; }

    /** 不可用时的原因。 */
    default String healthMessage() { return ""; }

    /** 异步获取工具列表（可包含健康检查）。 */
    default Mono<List<ToolSpec>> listToolsAsync() {
        return Mono.fromCallable(this::listTools);
    }
}
