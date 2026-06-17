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
 * Agent 工具策略 — 按任务匹配的技能或 Agent 绑定的技能过滤工具（07-工具动态感知 §4.8）。
 * <p>
 * 优先使用 {@link ForestNodeAdapter#metadata()} 中的 {@code _matchedSkillIds}（来自组合式路由的独立技能匹配结果），
 * 无匹配时回退到 Agent 定义中绑定的技能。
 */
@Component
public class AgentToolStrategy implements ToolRoutingStrategy {

    private static final Logger log = LoggerFactory.getLogger(AgentToolStrategy.class);

    /** 内部工具名（始终可用，不依赖 MCP 服务器选择）。 */
    private static final java.util.Set<String> INTERNAL_TOOL_NAMES = java.util.Set.of(
            "bash", "file_read", "file_write", "file_list", "file_search",
            "send_agent_message");

    private final ISkillRepository skillRepository;
    private final IToolUsageRepository toolUsageRepository;

    public AgentToolStrategy(ISkillRepository skillRepository,
                              IToolUsageRepository toolUsageRepository) {
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
        // 使用 task-matched skills（组合式路由：技能与智能体独立匹配）
        List<String> taskSkillIds = extractMatchedSkillIds(node);
        if (!taskSkillIds.isEmpty()) {
            log.debug("AgentToolStrategy: 使用 task-matched skills {} 个", taskSkillIds.size());
            return resolveToolsBySkillIds(taskSkillIds, registry, node.accountId());
        }

        // 无任务匹配技能时，检查 MCP 工具权限
        String mcpIds = node.metadata().get("_mcpServerIds");
        if (mcpIds == null || mcpIds.isBlank()) {
            // 未选择任何 MCP 服务器 → 仅返回内部工具
            log.debug("AgentToolStrategy: 无 MCP 服务器，仅内部工具");
            return registry.allSpecs().stream()
                    .filter(t -> INTERNAL_TOOL_NAMES.contains(t.name()))
                    .toList();
        }
        log.debug("AgentToolStrategy: MCP 已授权，回退全部工具");
        return registry.allSpecs();
    }

    /** 从 node metadata 中提取 task-matched skill IDs（组合式路由注入）。 */
    private List<String> extractMatchedSkillIds(ForestNodeAdapter node) {
        Object raw = node.metadata().get("_matchedSkillIds");
        if (!(raw instanceof String s) || s.isBlank()) return List.of();
        return List.of(s.split(","));
    }

    /** 按 skill ID 列表解析技能名 → 过滤工具 → 排序 → Top 10。 */
    private List<ToolSpec> resolveToolsBySkillIds(List<String> skillIds, ToolRegistry registry, UUID accountId) {
        // 1) 将 skillId 字符串解析为 UUID 并查询技能
        List<String> skillNames = skillIds.stream()
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

        if (skillNames.isEmpty()) {
            log.debug("AgentToolStrategy: matchedSkillIds 无有效技能，回退全部工具");
            return registry.allSpecs();
        }

        // 2) 按技能名过滤工具
        List<ToolSpec> matched = registry.allSpecs().stream()
                .filter(t -> skillNames.contains(t.name()))
                .collect(Collectors.toList());

        // 3) 按使用频率排序
        if (accountId != null) {
            matched = sortByUsage(matched, accountId);
        }

        log.info("AgentToolStrategy: task-matched 技能 {} 个，匹配工具 {} 个", skillNames.size(), matched.size());
        return matched.stream().limit(10).toList();
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
