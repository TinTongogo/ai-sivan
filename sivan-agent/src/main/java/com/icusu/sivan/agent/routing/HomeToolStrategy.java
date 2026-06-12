package com.icusu.sivan.agent.routing;

import com.icusu.sivan.core.tool.ToolProvider;
import com.icusu.sivan.core.tool.ToolRegistry;
import com.icusu.sivan.core.tool.ToolSpec;
import com.icusu.sivan.domain.forest.context.ExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * HomeTask 工具策略 — 按 metadata.serverId 直连 MCP 服务器（07-工具动态感知 §4.8）。
 * <p>
 * 适用于 {@code supportedLeafType() = "home_task"} 的叶子节点。
 * 只返回指定 MCP 服务器的工具，用于智能家居等直连场景。
 */
@Component
public class HomeToolStrategy implements ToolRoutingStrategy {

    private static final Logger log = LoggerFactory.getLogger(HomeToolStrategy.class);

    @Override
    public String supportedLeafType() {
        return "home_task";
    }

    @Override
    public List<ToolSpec> resolve(ForestNodeAdapter node, String taskDescription,
                                   ToolRegistry registry, ExecutionContext ctx) {
        String serverId = node.serverId();
        if (serverId == null || serverId.isBlank()) {
            log.debug("HomeToolStrategy: 无 serverId，回退全部工具");
            return registry.allSpecs();
        }

        // 只返回指定 MCP 服务器的工具
        List<ToolSpec> result = registry.allSpecs().stream()
                .filter(t -> {
                    ToolProvider provider = registry.findProvider(t.name());
                    return provider != null && serverId.equals(provider.providerId());
                })
                .toList();

        log.info("HomeToolStrategy: serverId={} 匹配工具 {} 个", serverId, result.size());
        return result;
    }
}
