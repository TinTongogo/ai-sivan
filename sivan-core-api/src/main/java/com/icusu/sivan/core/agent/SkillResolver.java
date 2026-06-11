package com.icusu.sivan.core.agent;

import com.icusu.sivan.core.tool.ToolSpec;

import java.util.List;
import java.util.UUID;

/**
 * 技能解析器 — 根据 agent 名称和任务内容动态绑定工具技能。
 * <p>
 * 执行时由 AgentLeafExecutor 调用，按 agent 已绑定的技能和任务内容
 * 解析出可用工具列表。需要扩展时调用 LLM 生成新工具方案。
 * </p>
 * 放在 sivan-core-api 层因为依赖 {@link ToolSpec}。
 */
@FunctionalInterface
public interface SkillResolver {

    /**
     * 为指定 agent 解析可用工具列表。
     * @param agentName   agent 名称
     * @param taskContent 当前任务内容
     * @param accountId   账户 ID
     * @return 可用工具列表
     */
    List<ToolSpec> resolve(String agentName, String taskContent, UUID accountId);
}
