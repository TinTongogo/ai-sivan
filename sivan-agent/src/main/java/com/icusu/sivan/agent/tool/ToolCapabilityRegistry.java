package com.icusu.sivan.agent.tool;

import io.modelcontextprotocol.spec.McpSchema;

import java.util.List;
import java.util.Set;

/**
 * 工具能力注册表，负责推断 MCP 工具的语义能力标签。
 * <p>
 * 替代 {@code ToolIndex.extractCapabilities()} 的内联逻辑，
 * 支持可注册的 {@link CapabilityMatcher} 策略组合。
 */
public interface ToolCapabilityRegistry {

    /**
     * 推断工具的能力集合。
     *
     * @param tool MCP 工具元数据
     * @return 能力标签列表，可用于 {@link com.icusu.sivan.domain.tool.ToolMeta#setCapabilities}
     */
    List<String> resolve(McpSchema.Tool tool);

    /**
     * 批量推断工具的能力集合。默认实现逐工具调用 {@link #resolve}，实现类可覆盖为批量 Embedding 减少 HTTP 请求。
     *
     * @param tools MCP 工具列表
     * @return 能力标签列表（顺序与输入一致）
     */
    default List<List<String>> resolveAll(List<McpSchema.Tool> tools) {
        return tools.stream().map(this::resolve).toList();
    }

    /**
     * 注册自定义能力匹配策略。
     *
     * @param matcher 匹配策略
     */
    void registerMatcher(CapabilityMatcher matcher);

    /**
     * 获取已注册的所有能力标签（用于元数据浏览）。
     */
    Set<ToolCapability> allCapabilities();
}
