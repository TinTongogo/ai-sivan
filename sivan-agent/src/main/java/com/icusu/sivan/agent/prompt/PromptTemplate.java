
package com.icusu.sivan.agent.prompt;

import java.util.Map;

/**
 * 提示词模板引擎，将变量渲染为 Prompt 值对象。
 * <p>
 * 当前 Prompt 工厂类（AgentPrompts、SkillPrompts 等）中的工厂方法
 * 本质上就是模板渲染：接收结构化参数，返回 Prompt。
 */
@FunctionalInterface
public interface PromptTemplate {

    /** 使用给定变量渲染提示词。 */
    Prompt render(Map<String, Object> variables);

    /** 模板唯一标识，用于调用统计和缓存键。 */
    default String templateId() {
        return getClass().getSimpleName();
    }
}
