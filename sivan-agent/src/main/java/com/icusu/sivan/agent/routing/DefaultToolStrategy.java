package com.icusu.sivan.agent.routing;

import com.icusu.sivan.core.tool.ToolRegistry;
import com.icusu.sivan.core.tool.ToolSpec;
import com.icusu.sivan.domain.forest.context.ExecutionContext;
import com.icusu.sivan.domain.tool.IToolUsageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 默认工具策略 — 按使用次数排序返回全部工具（07-工具动态感知 §4.8）。
 * <p>
 * 兜底策略，当没有其他策略匹配叶子类型时使用。
 * 按历史使用频率降序排列工具，返回前 10 个。
 */
@Component
public class DefaultToolStrategy implements ToolRoutingStrategy {

    private static final Logger log = LoggerFactory.getLogger(DefaultToolStrategy.class);

    private final IToolUsageRepository toolUsageRepository;

    public DefaultToolStrategy(IToolUsageRepository toolUsageRepository) {
        this.toolUsageRepository = toolUsageRepository;
    }

    @Override
    public String supportedLeafType() {
        return "*"; // 通配符，兜底匹配任何叶子类型
    }

    @Override
    public List<ToolSpec> resolve(ForestNodeAdapter node, String taskDescription,
                                   ToolRegistry registry, ExecutionContext ctx) {
        List<ToolSpec> tools = registry.allSpecs();

        // 按使用频率排序
        if (node.accountId() != null) {
            tools = sortByUsage(tools, node.accountId());
        }

        log.debug("DefaultToolStrategy: 返回 {} 个工具", tools.size());
        return tools.stream().limit(10).toList();
    }

    private List<ToolSpec> sortByUsage(List<ToolSpec> tools, UUID accountId) {
        try {
            List<Object[]> usageData = toolUsageRepository.countByToolName(accountId);
            if (usageData == null || usageData.isEmpty()) return tools;

            Map<String, Long> freqMap = usageData.stream()
                    .collect(Collectors.toMap(
                            row -> (String) row[0],
                            row -> ((Number) row[1]).longValue()
                    ));
            return tools.stream()
                    .sorted(Comparator.<ToolSpec, Long>comparing(
                            t -> freqMap.getOrDefault(t.name(), 0L),
                            Comparator.reverseOrder()
                    ))
                    .toList();
        } catch (Exception e) {
            log.warn("工具使用频率排序失败，使用原始顺序: {}", e.getMessage());
            return tools;
        }
    }
}
