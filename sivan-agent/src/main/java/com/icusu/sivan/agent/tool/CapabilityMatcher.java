package com.icusu.sivan.agent.tool;

import java.util.Set;

/**
 * 工具能力匹配策略。
 * <p>
 * 根据工具名称、描述等特征推断工具所具备的能力。
 * 可注册多个匹配器到 {@link ToolCapabilityRegistry}，组合判定。
 */
@FunctionalInterface
public interface CapabilityMatcher {

    /**
     * 根据工具描述推断能力集合。
     *
     * @param toolName        工具名称
     * @param toolDescription 工具描述
     * @return 匹配到的能力集合（不为 null）
     */
    Set<ToolCapability> match(String toolName, String toolDescription);
}
