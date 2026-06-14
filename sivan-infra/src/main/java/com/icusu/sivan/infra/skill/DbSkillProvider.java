package com.icusu.sivan.infra.skill;

import com.icusu.sivan.core.tool.SkillProvider;
import com.icusu.sivan.core.tool.SkillSpec;
import com.icusu.sivan.domain.agent.ISkillRepository;
import com.icusu.sivan.domain.agent.Skill;

import java.util.List;
import java.util.UUID;

/**
 * 基于数据库的 {@link SkillProvider} 实现，按账户加载技能。
 */
public class DbSkillProvider implements SkillProvider {

    private final UUID accountId;
    private final ISkillRepository skillRepository;

    public DbSkillProvider(UUID accountId, ISkillRepository skillRepository) {
        this.accountId = accountId;
        this.skillRepository = skillRepository;
    }

    @Override
    public List<SkillSpec> listSkills() {
        return skillRepository.findAllActiveByAccount(accountId).stream()
                .map(s -> new SkillSpec(s.getSkillCode(), s.getName(),
                        s.getDescription(), s.getCategory()))
                .toList();
    }

    @Override
    public String loadContent(String skillCode) {
        return skillRepository.findByAccountAndCode(accountId, skillCode)
                .map(Skill::getContent)
                .orElse(null);
    }
}
