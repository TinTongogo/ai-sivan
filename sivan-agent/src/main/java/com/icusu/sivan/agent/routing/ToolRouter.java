package com.icusu.sivan.agent.routing;

import com.icusu.sivan.core.tool.ToolRegistry;
import com.icusu.sivan.core.tool.ToolSpec;
import com.icusu.sivan.domain.forest.context.ExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 工具路由器 — 按叶子类型匹配策略并解析可用工具（07-工具动态感知 §4.8）。
 * <p>
 * 核心流程：
 * <ol>
 *   <li>根据叶子节点类型找到对应 {@link ToolRoutingStrategy}</li>
 *   <li>调用策略的 {@code resolve()} 方法获取匹配工具列表</li>
 *   <li>无匹配策略时使用 {@link DefaultToolStrategy} 兜底</li>
 * </ol>
 */
@Component
public class ToolRouter {

    private static final Logger log = LoggerFactory.getLogger(ToolRouter.class);

    private final ToolRegistry registry;
    private final List<ToolRoutingStrategy> strategies;
    private final DefaultToolStrategy fallback;

    public ToolRouter(ToolRegistry registry,
                      List<ToolRoutingStrategy> strategies,
                      DefaultToolStrategy fallback) {
        this.registry = registry;
        this.strategies = strategies;
        this.fallback = fallback;
    }

    /**
     * 为叶子节点解析可用工具。
     *
     * @param node            叶子节点适配器
     * @param taskDescription 任务描述文本
     * @param ctx             执行上下文
     * @return 匹配的工具列表
     */
    public List<ToolSpec> resolve(ForestNodeAdapter node, String taskDescription, ExecutionContext ctx) {
        ToolRoutingStrategy strategy = strategies.stream()
                .filter(s -> s.supportedLeafType().equals(node.nodeType()))
                .findFirst()
                .orElse(fallback);

        log.debug("ToolRouter: nodeType={} strategy={}", node.nodeType(), strategy.getClass().getSimpleName());
        return strategy.resolve(node, taskDescription, registry, ctx);
    }
}
