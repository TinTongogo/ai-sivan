package com.icusu.sivan.core.tool;

import java.util.List;

/**
 * 技能提供者端口。智能体通过此端口加载技能知识。
 * 与 {@link ToolProvider} 对等，同为核心运行时能力。
 */
public interface SkillProvider {

    /** 空实现，无任何技能。 */
    SkillProvider EMPTY = new SkillProvider() {
        @Override public List<SkillSpec> listSkills() { return List.of(); }
        @Override public String loadContent(String skillCode) { return null; }
    };

    /** 列出当前智能体可用的所有技能。 */
    List<SkillSpec> listSkills();

    /** 按技能编码加载完整内容文本，不存在返回 null。 */
    String loadContent(String skillCode);
}
