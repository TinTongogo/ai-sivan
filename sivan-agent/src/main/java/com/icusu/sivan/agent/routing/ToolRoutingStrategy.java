package com.icusu.sivan.agent.routing;

import com.icusu.sivan.core.tool.ToolRegistry;
import com.icusu.sivan.core.tool.ToolSpec;
import com.icusu.sivan.domain.forest.context.ExecutionContext;

import java.util.List;

/**
 * 工具路由策略接口（07-工具动态感知 §4.5）。
 * <p>
 * 每种叶子类型对应一个策略实现，按 {@link #supportedLeafType()} 匹配。
 */
public interface ToolRoutingStrategy {

    /** 本策略适用的叶子类型（如 "task" / "home_task" / "chat"）。 */
    String supportedLeafType();

    /** 为叶子节点选择可用工具。 */
    List<ToolSpec> resolve(ForestNodeAdapter node, String taskDescription, ToolRegistry registry, ExecutionContext ctx);
}
