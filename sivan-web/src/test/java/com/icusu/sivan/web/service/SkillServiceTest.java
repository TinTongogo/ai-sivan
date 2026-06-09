package com.icusu.sivan.web.service;

import com.icusu.sivan.common.exception.DomainException;
import com.icusu.sivan.domain.agent.Skill;
import com.icusu.sivan.domain.agent.ISkillRepository;
import com.icusu.sivan.web.agent.dto.CreateSkillRequest;
import com.icusu.sivan.web.agent.dto.UpdateSkillRequest;
import com.icusu.sivan.web.agent.dto.SkillResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
/** 技能服务测试。 */
class SkillServiceTest {

    @Mock
    private ISkillRepository skillRepository;

    private SkillService skillService;

    private final UUID accountId = UUID.randomUUID();

    @BeforeEach
    /** 初始化测试环境。 */
    void setUp() {
        skillService = new SkillService(skillRepository);
    }

    @Test
    /** 创建技能成功。 */
    void create_shouldSucceed() {
        CreateSkillRequest request = new CreateSkillRequest();
        request.setSkillCode("code-review");
        request.setName("代码审查");
        request.setDescription("审查代码质量");
        request.setContent("请审查以下代码并给出改进建议");

        when(skillRepository.findByAccountAndCode(accountId, "code-review")).thenReturn(Optional.empty());

        SkillResponse response = skillService.create(accountId, request);

        assertEquals("code-review", response.getSkillCode());
        assertEquals("代码审查", response.getName());
        assertEquals("ACTIVE", response.getStatus());
        verify(skillRepository).save(any(Skill.class));
    }

    @Test
    /** 创建重复编码的技能应抛出异常。 */
    void create_shouldThrowWhenCodeExists() {
        CreateSkillRequest request = new CreateSkillRequest();
        request.setSkillCode("duplicate");
        request.setName("重复");

        when(skillRepository.findByAccountAndCode(accountId, "duplicate"))
                .thenReturn(Optional.of(new Skill()));

        assertThrows(DomainException.class, () -> skillService.create(accountId, request));
        verify(skillRepository, never()).save(any());
    }

    @Test
    /** 根据 ID 获取技能。 */
    void getById_shouldReturnSkill() {
        UUID skillId = UUID.randomUUID();
        Skill skill = Skill.builder()
                .skillId(skillId).accountId(accountId).skillCode("test")
                .name("测试技能").build();

        when(skillRepository.findById(skillId)).thenReturn(Optional.of(skill));

        SkillResponse response = skillService.getById(accountId, skillId);

        assertEquals("test", response.getSkillCode());
    }

    @Test
    /** 获取非本人技能应抛出异常。 */
    void getById_shouldThrowWhenNotOwned() {
        UUID skillId = UUID.randomUUID();
        Skill skill = Skill.builder()
                .skillId(skillId).accountId(UUID.randomUUID()).build();

        when(skillRepository.findById(skillId)).thenReturn(Optional.of(skill));

        assertThrows(DomainException.class, () -> skillService.getById(accountId, skillId));
    }

    @Test
    /** 列出所有技能。 */
    void list_shouldReturnAllWhenNoCategory() {
        Skill s1 = Skill.builder()
                .skillId(UUID.randomUUID()).accountId(accountId).skillCode("S1").build();
        when(skillRepository.findAllByAccount(accountId)).thenReturn(List.of(s1));

        List<SkillResponse> list = skillService.list(accountId, null);

        assertEquals(1, list.size());
        verify(skillRepository).findAllByAccount(accountId);
    }

    @Test
    /** 按分类过滤技能列表。 */
    void list_shouldFilterByCategory() {
        String category = "security";
        when(skillRepository.findAllByAccount(accountId)).thenReturn(List.of());

        List<SkillResponse> list = skillService.list(accountId, category);

        assertTrue(list.isEmpty());
        verify(skillRepository).findAllByAccount(accountId);
    }

    @Test
    /** 更新技能信息。 */
    void update_shouldModifyFields() {
        UUID skillId = UUID.randomUUID();
        Skill skill = Skill.builder()
                .skillId(skillId).accountId(accountId).skillCode("test")
                .name("旧名称").build();

        when(skillRepository.findById(skillId)).thenReturn(Optional.of(skill));

        UpdateSkillRequest request = new UpdateSkillRequest();
        request.setName("新名称");
        request.setCategory("new-category");

        SkillResponse response = skillService.update(accountId, skillId, request);

        assertEquals("新名称", response.getName());
        assertEquals("new-category", response.getCategory());
    }

    @Test
    /** 删除技能。 */
    void delete_shouldRemoveSkill() {
        UUID skillId = UUID.randomUUID();
        Skill skill = Skill.builder()
                .skillId(skillId).accountId(accountId).build();

        when(skillRepository.findById(skillId)).thenReturn(Optional.of(skill));

        skillService.delete(accountId, skillId);

        verify(skillRepository).delete(skillId);
    }
}
