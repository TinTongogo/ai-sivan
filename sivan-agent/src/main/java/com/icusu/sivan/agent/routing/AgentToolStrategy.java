package com.icusu.sivan.agent.routing;

import com.icusu.sivan.core.tool.ToolRegistry;
import com.icusu.sivan.core.tool.ToolSpec;
import com.icusu.sivan.domain.agent.IAgentRepository;
import com.icusu.sivan.domain.agent.ISkillRepository;
import com.icusu.sivan.domain.agent.Skill;
import com.icusu.sivan.domain.forest.context.ExecutionContext;
import com.icusu.sivan.domain.tool.IToolUsageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Agent 工具策略 — 按 Agent 绑定的技能匹配工具（07-工具动态感知 §4.8）。
 * <p>
 * 适用于 {@code supportedLeafType() = "task"} 的叶子节点。
 * 根据 Agent 定义中绑定的技能名称，从注册表中筛选匹配的工具，按使用次数降序排列。
 */
@Component
public class AgentToolStrategy implements ToolRoutingStrategy {

    private static final Logger log = LoggerFactory.getLogger(AgentToolStrategy.class);

    private final IAgentRepository agentRepository;
    private final ISkillRepository skillRepository;
    private final IToolUsageRepository toolUsageRepository;

    public AgentToolStrategy(IAgentRepository agentRepository,
                              ISkillRepository skillRepository,
                              IToolUsageRepository toolUsageRepository) {
        this.agentRepository = agentRepository;
        this.skillRepository = skillRepository;
        this.toolUsageRepository = toolUsageRepository;
    }

    @Override
    public String supportedLeafType() {
        return "task";
    }

    @Override
    public List<ToolSpec> resolve(ForestNodeAdapter node, String taskDescription,
                                   ToolRegistry registry, ExecutionContext ctx) {
        String agentName = node.agentName();
        if (agentName == null || agentName.isBlank()) {
            log.debug("AgentToolStrategy: 无 agentName，回退全部工具");
            return registry.allSpecs();
        }

        // 查询该 Agent 绑定的技能名列表
        List<String> skillNames = resolveSkillNames(node.accountId(), agentName);
        if (skillNames.isEmpty()) {
            log.debug("AgentToolStrategy: Agent {} 无关联技能，回退全部工具", agentName);
            return registry.allSpecs();
        }

        // 按技能名过滤工具
        List<ToolSpec> matched = registry.allSpecs().stream()
                .filter(t -> skillNames.contains(t.name()))
                .collect(Collectors.toList());

        // 按使用频率排序
        if (node.accountId() != null) {
            matched = sortByUsage(matched, node.accountId());
        }

        log.info("AgentToolStrategy: agent={} 匹配工具 {} 个 (技能 {} 个)", agentName, matched.size(), skillNames.size());
        return matched.stream().limit(10).toList();
    }

    /** 解析 Agent 绑定的技能名列表。 */
    private List<String> resolveSkillNames(UUID accountId, String agentName) {
        try {
            var agentOpt = agentRepository.findByAccountAndName(accountId, agentName);
            if (agentOpt.isEmpty() || agentOpt.get().getSkillIds() == null) {
                return List.of();
            }
            return agentOpt.get().getSkillIds().stream()
                    .map(id -> {
                        try {
                            return skillRepository.findById(UUID.fromString(id));
                        } catch (Exception e) {
                            return Optional.<Skill>empty();
                        }
                    })
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .map(Skill::getName)
                    .filter(Objects::nonNull)
                    .toList();
        } catch (Exception e) {
            log.warn("AgentToolStrategy: 解析 Agent 技能失败: {}", e.getMessage());
            return List.of();
        }
    }

    /** 按历史使用频率降序排列。 */
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
