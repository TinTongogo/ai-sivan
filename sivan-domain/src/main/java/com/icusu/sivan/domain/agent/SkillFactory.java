package com.icusu.sivan.domain.agent;

import com.icusu.sivan.common.enums.SkillStatus;
import com.icusu.sivan.common.enums.SkillType;

import java.util.List;
import java.util.UUID;

/**
 * Skill 领域工厂。封装 Skill 创建逻辑，确保默认值一致。
 */
public final class SkillFactory {

    private SkillFactory() {}

    /** 创建用户手动定义的技能。 */
    public static Skill createUserSkill(UUID accountId, UUID projectId, String skillCode,
                                         String name, String displayName, String description,
                                         String content, String category, List<String> tags) {
        return Skill.builder()
                .accountId(accountId)
                .projectId(projectId)
                .skillCode(skillCode)
                .name(name)
                .displayName(displayName)
                .description(description)
                .content(content)
                .category(category)
                .tags(tags)
                .skillType(SkillType.USER)
                .status(SkillStatus.ACTIVE)
                .usageCount(0)
                .build();
    }

    /** 创建系统自动生成的技能。 */
    public static Skill createSystemSkill(UUID accountId, String skillCode, String name,
                                           String description, String content, String category) {
        return Skill.builder()
                .accountId(accountId)
                .skillCode(skillCode)
                .name(name)
                .displayName(name)
                .description(description)
                .content(content)
                .category(category)
                .skillType(SkillType.SYSTEM)
                .status(SkillStatus.ACTIVE)
                .usageCount(0)
                .build();
    }
}
