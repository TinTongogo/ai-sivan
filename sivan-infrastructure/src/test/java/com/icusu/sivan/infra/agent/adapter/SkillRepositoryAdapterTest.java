package com.icusu.sivan.infra.agent.adapter;

import com.icusu.sivan.common.enums.SkillStatus;
import com.icusu.sivan.domain.agent.ISkillRepository;
import com.icusu.sivan.domain.agent.Skill;
import com.icusu.sivan.infra.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Sql("/disable-fk.sql")
@Transactional
class SkillRepositoryAdapterTest extends AbstractIntegrationTest {

    @Autowired
    private ISkillRepository repository;

    @Test
    void shouldSaveAndFindById() {
        Skill skill = Skill.builder()
                .accountId(UUID.randomUUID())
                .skillCode("test-code")
                .name("测试技能")
                .description("测试描述")
                .status(SkillStatus.ACTIVE)
                .build();
        repository.save(skill);

        assertNotNull(skill.getSkillId());

        Skill found = repository.findById(skill.getSkillId()).orElse(null);
        assertNotNull(found);
        assertEquals("test-code", found.getSkillCode());
        assertEquals("测试技能", found.getName());
    }

    @Test
    void shouldFindByAccountAndCode() {
        UUID accountId = UUID.randomUUID();
        repository.save(Skill.builder()
                .accountId(accountId).skillCode("unique-code").name("唯一技能")
                .status(SkillStatus.ACTIVE).build());

        Skill found = repository.findByAccountAndCode(accountId, "unique-code").orElse(null);
        assertNotNull(found);
    }

    @Test
    void shouldFindByAccountAndName() {
        UUID accountId = UUID.randomUUID();
        repository.save(Skill.builder()
                .accountId(accountId).skillCode("sc").name("按名查找")
                .status(SkillStatus.ACTIVE).build());

        Skill found = repository.findByAccountAndName(accountId, "按名查找").orElse(null);
        assertNotNull(found);
    }

    @Test
    void shouldFindByAccountAndProjectAndName() {
        UUID accountId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        repository.save(Skill.builder()
                .accountId(accountId).projectId(projectId).skillCode("sc-p")
                .name("项目技能").status(SkillStatus.ACTIVE).build());

        Skill found = repository.findByAccountAndProjectAndName(accountId, projectId, "项目技能").orElse(null);
        assertNotNull(found);
    }

    @Test
    void shouldFindAllByAccount() {
        UUID accountId = UUID.randomUUID();
        for (int i = 0; i < 3; i++) {
            repository.save(Skill.builder()
                    .accountId(accountId).skillCode("s" + i).name("技能" + i)
                    .status(SkillStatus.ACTIVE).build());
        }

        List<Skill> skills = repository.findAllByAccount(accountId);
        assertEquals(3, skills.size());
    }

    @Test
    void shouldFindAllActiveByAccount() {
        UUID accountId = UUID.randomUUID();
        repository.save(Skill.builder()
                .accountId(accountId).skillCode("a1").name("活跃")
                .status(SkillStatus.ACTIVE).build());
        repository.save(Skill.builder()
                .accountId(accountId).skillCode("a2").name("非活跃")
                .status(SkillStatus.INACTIVE).build());

        List<Skill> active = repository.findAllActiveByAccount(accountId);
        assertEquals(1, active.size());
        assertEquals("活跃", active.get(0).getName());
    }

    @Test
    void shouldDelete() {
        Skill skill = Skill.builder()
                .accountId(UUID.randomUUID()).skillCode("del").name("待删除")
                .status(SkillStatus.ACTIVE).build();
        repository.save(skill);
        UUID id = skill.getSkillId();

        repository.delete(id);
        assertTrue(repository.findById(id).isEmpty());
    }
}
