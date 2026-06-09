package com.icusu.sivan.web.service;

import com.icusu.sivan.common.enums.SkillStatus;
import com.icusu.sivan.common.exception.DomainException;
import com.icusu.sivan.common.exception.ResourceNotFoundException;
import com.icusu.sivan.domain.agent.Skill;
import com.icusu.sivan.domain.agent.SkillFactory;
import com.icusu.sivan.domain.agent.ISkillRepository;
import com.icusu.sivan.web.agent.dto.CreateSkillRequest;
import com.icusu.sivan.web.agent.dto.UpdateSkillRequest;
import com.icusu.sivan.web.agent.dto.SkillResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** 技能管理服务，管理智能体技能。 */
@Service
@RequiredArgsConstructor
public class SkillService {

    private final ISkillRepository skillRepository;

    /** 创建技能。 */
    public SkillResponse create(UUID accountId, CreateSkillRequest request) {
        if (skillRepository.findByAccountAndCode(accountId, request.getSkillCode()).isPresent()) {
            throw DomainException.conflict("技能代码已存在");
        }

        Skill skill = SkillFactory.createUserSkill(accountId, request.getProjectId(),
                request.getSkillCode(), request.getName(), request.getDisplayName(),
                request.getDescription(), request.getContent(), request.getCategory(),
                request.getTags() != null ? request.getTags() : List.of());

        skillRepository.save(skill);
        return toResponse(skill);
    }

    /** 根据 ID 查询技能。 */
    public SkillResponse getById(UUID accountId, UUID skillId) {
        Skill skill = findOwned(accountId, skillId);
        return toResponse(skill);
    }

    /** 查询技能列表，可按分类过滤（保留向后兼容）。 */
    public List<SkillResponse> list(UUID accountId, String category) {
        return list(accountId, category, null);
    }

    /** 查询技能列表，可按分类和来源过滤。 */
    public List<SkillResponse> list(UUID accountId, String category, String skillType) {
        List<Skill> skills = skillRepository.findAllByAccount(accountId);
        if (category != null) {
            skills = skills.stream().filter(s -> category.equals(s.getCategory())).toList();
        }
        if (skillType != null) {
            skills = skills.stream().filter(s -> s.getSkillType() != null
                    && skillType.equals(s.getSkillType().name())).toList();
        }
        return skills.stream().map(this::toResponse).toList();
    }

    /** 更新技能配置。 */
    public SkillResponse update(UUID accountId, UUID skillId, UpdateSkillRequest request) {
        Skill skill = findOwned(accountId, skillId);
        skill.updateFrom(request.getName(), request.getDisplayName(), request.getDescription(),
                request.getContent(), request.getCategory(), request.getTags(), request.getProjectId(),
                request.getStatus() != null ? SkillStatus.valueOf(request.getStatus()) : null);
        skillRepository.save(skill);
        return toResponse(skill);
    }

    /** 删除技能。 */
    public void delete(UUID accountId, UUID skillId) {
        Skill skill = findOwned(accountId, skillId);
        skillRepository.delete(skill.getSkillId());
    }

    /** 批量删除技能（校验所有权后删除）。 */
    @Transactional
    public void deleteBatch(java.util.List<UUID> ids, UUID accountId) {
        if (ids == null || ids.isEmpty()) return;
        for (UUID id : ids) {
            Skill skill = findOwned(accountId, id);
            skillRepository.delete(skill.getSkillId());
        }
    }

    /** 查找当前用户拥有的技能。 */
    private Skill findOwned(UUID accountId, UUID skillId) {
        Skill skill = skillRepository.findById(skillId)
                .orElseThrow(() -> ResourceNotFoundException.notFound("技能", skillId));
        if (!skill.getAccountId().equals(accountId)) {
            throw ResourceNotFoundException.notFound("技能", skillId);
        }
        return skill;
    }

    /** 转换为响应对象。 */
    private SkillResponse toResponse(Skill skill) {
        return SkillResponse.builder()
                .skillId(skill.getSkillId())
                .skillCode(skill.getSkillCode())
                .name(skill.getName())
                .displayName(skill.getDisplayName())
                .description(skill.getDescription())
                .content(skill.getContent())
                .category(skill.getCategory())
                .skillType(skill.getSkillType() != null ? skill.getSkillType().name() : null)
                .tags(skill.getTags())
                .usageCount(skill.getUsageCount())
                .lastUsedAt(skill.getLastUsedAt())
                .status(skill.getStatus() != null ? skill.getStatus().name() : null)
                .createdAt(skill.getCreatedAt())
                .updatedAt(skill.getUpdatedAt())
                .build();
    }
}
