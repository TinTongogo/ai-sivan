package com.icusu.sivan.domain.agent;

/**
 * 技能的可执行契约接口。
 * <p>
 * 技能是一段结构化的可加载知识，智能体在需要时按名加载。
 * {@link com.icusu.sivan.domain.agent.Skill} 实体实现了此契约的数据面。
 */
public interface SkillDefinition {

    /** 技能唯一编码。 */
    String getSkillCode();

    /** 技能名称。 */
    String getName();

    /** 技能完整内容（定位、工作流、验收标准）。 */
    String getContent();

    /** 技能描述。 */
    String getDescription();

    /** 技能分类标签。 */
    String getCategory();

    /** 是否处于激活状态。 */
    boolean isActive();

    /** 记录一次使用。 */
    void recordUsage();
}
