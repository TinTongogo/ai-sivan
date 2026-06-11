package com.icusu.sivan.agent.tool;

import com.icusu.sivan.core.agent.SkillResolver;
import com.icusu.sivan.core.tool.ToolSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * 技能解析器实现 — 通过 {@link DefaultToolResolver#resolveForAgent} 按 agent 配置 + 语义匹配解析工具。
 * <p>
 * 匹配策略：
 * <ol>
 *   <li>调用 {@link DefaultToolResolver#resolveForAgent} 获取 agent 匹配的工具</li>
 *   <li>按置信度阈值过滤（≥0.5）</li>
 *   <li>返回 {@link ToolSpec} 列表</li>
 * </ol>
 */
@Component
public class SkillResolverImpl implements SkillResolver {

    private static final Logger log = LoggerFactory.getLogger(SkillResolverImpl.class);

    private final DefaultToolResolver toolResolver;

    public SkillResolverImpl(DefaultToolResolver toolResolver) {
        this.toolResolver = toolResolver;
    }

    @Override
    public List<ToolSpec> resolve(String agentName, String taskContent, UUID accountId) {
        if (agentName == null || agentName.isBlank()) {
            log.debug("[技能解析] agentName 为空，返回空工具列表");
            return List.of();
        }

        MatchedTools matched = toolResolver.resolveForAgent(agentName, accountId);
        if (matched == null || matched.isEmpty()) {
            log.debug("[技能解析] agent={} 无匹配工具", agentName);
            return List.of();
        }

        // resolveForAgent 已按 agent 配置做了语义匹配，返回的 schemas 即为匹配结果
        List<ToolSpec> result = matched.schemas();

        log.info("[技能解析] agent={} 匹配 {} 个工具", agentName, result.size());
        return result;
    }
}
