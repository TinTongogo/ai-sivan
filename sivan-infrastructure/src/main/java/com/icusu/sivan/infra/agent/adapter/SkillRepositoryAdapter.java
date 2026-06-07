package com.icusu.sivan.infra.agent.adapter;

import com.icusu.sivan.common.enums.SkillStatus;
import com.icusu.sivan.common.enums.SkillType;
import com.icusu.sivan.domain.agent.Skill;
import com.icusu.sivan.domain.agent.ISkillRepository;
import com.icusu.sivan.infra.agent.entity.SkillEntity;
import com.icusu.sivan.infra.agent.repository.SkillJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 技能仓储适配器，实现 ISkillRepository。
 */
@Component
@RequiredArgsConstructor
public class SkillRepositoryAdapter implements ISkillRepository {

    private final SkillJpaRepository jpaRepository;

    /**
     * 根据 ID 查询技能。
     */
    @Override
    public Optional<Skill> findById(UUID skillId) {
        return jpaRepository.findById(skillId).map(this::toDomain);
    }

    /**
     * 根据账号和编码查询技能。
     */
    @Override
    public Optional<Skill> findByAccountAndCode(UUID accountId, String skillCode) {
        return jpaRepository.findByAccountIdAndSkillCode(accountId, skillCode).map(this::toDomain);
    }

    /**
     * 根据账号和名称查询技能。
     */
    @Override
    public Optional<Skill> findByAccountAndName(UUID accountId, String name) {
        return jpaRepository.findByAccountIdAndName(accountId, name).map(this::toDomain);
    }

    /**
     * 根据账号、项目和名称查询技能。
     */
    @Override
    public Optional<Skill> findByAccountAndProjectAndName(UUID accountId, UUID projectId, String name) {
        return jpaRepository.findByAccountIdAndProjectIdAndName(accountId, projectId, name)
                .map(this::toDomain);
    }

    /**
     * 查询账号下所有技能。
     */
    @Override
    public List<Skill> findAllByAccount(UUID accountId) {
        return jpaRepository.findByAccountId(accountId).stream().map(this::toDomain).toList();
    }

    /**
     * 查询账号下所有已激活的技能。
     */
    @Override
    public List<Skill> findAllActiveByAccount(UUID accountId) {
        return jpaRepository.findByAccountIdAndStatus(accountId, "ACTIVE").stream()
                .map(this::toDomain).toList();
    }

    /**
     * 查询账号下指定项目中的技能。
     */
    @Override
    public List<Skill> findAllByAccountAndProject(UUID accountId, UUID projectId) {
        return jpaRepository.findByAccountIdAndProjectId(accountId, projectId).stream()
                .map(this::toDomain).toList();
    }

    /**
     * 查询账号下指定分类的技能。
     */
    @Override
    public List<Skill> findAllByAccountAndCategory(UUID accountId, String category) {
        return jpaRepository.findByAccountIdAndCategory(accountId, category).stream()
                .map(this::toDomain).toList();
    }

    /**
     * 保存技能，回写 ID 和时间戳。
     */
    @Override
    public void save(Skill skill) {
        SkillEntity entity = toEntity(skill);
        jpaRepository.save(entity);
        if (skill.getSkillId() == null) {
            skill.setSkillId(entity.getSkillId());
        }
        skill.setCreatedAt(entity.getCreatedAt() != null ? entity.getCreatedAt().toLocalDateTime() : null);
        skill.setUpdatedAt(entity.getUpdatedAt() != null ? entity.getUpdatedAt().toLocalDateTime() : null);
    }

    /**
     * 根据 ID 删除技能。
     */
    @Override
    public void delete(UUID skillId) {
        jpaRepository.deleteById(skillId);
    }

    @Override
    public void deleteBatch(java.util.List<UUID> ids) {
        if (ids != null && !ids.isEmpty()) jpaRepository.deleteAllById(ids);
    }

    /**
     * 检查是否存在同名技能（排除指定 ID）。
     */
    @Override
    public boolean existsByCodeExcludingId(UUID accountId, String skillCode, UUID excludeId) {
        return jpaRepository.existsByAccountIdAndSkillCodeAndSkillIdNot(accountId, skillCode, excludeId);
    }

    /**
     * 统计账号下技能总数。
     */
    @Override
    public long countByAccount(UUID accountId) {
        return jpaRepository.countByAccountId(accountId);
    }

    /**
     * 将实体转换为领域对象。
     */
    private Skill toDomain(SkillEntity entity) {
        return Skill.builder()
                .skillId(entity.getSkillId())
                .accountId(entity.getAccountId())
                .projectId(entity.getProjectId())
                .skillCode(entity.getSkillCode())
                .name(entity.getName())
                .displayName(entity.getDisplayName())
                .description(entity.getDescription())
                .content(entity.getContent())
                .category(entity.getCategory())
                .tags(parseStringArray(entity.getTags()))
                .usageCount(entity.getUsageCount())
                .lastUsedAt(entity.getLastUsedAt() != null ? entity.getLastUsedAt().toLocalDateTime() : null)
                .status(entity.getStatus() != null ? SkillStatus.valueOf(entity.getStatus()) : SkillStatus.ACTIVE)
                .skillType(entity.getSkillType() != null ? SkillType.valueOf(entity.getSkillType()) : SkillType.USER)
                .createdAt(entity.getCreatedAt() != null ? entity.getCreatedAt().toLocalDateTime() : null)
                .updatedAt(entity.getUpdatedAt() != null ? entity.getUpdatedAt().toLocalDateTime() : null)
                .build();
    }

    /**
     * 将领域对象转换为实体。
     */
    private SkillEntity toEntity(Skill skill) {
        SkillEntity entity = new SkillEntity();
        entity.setSkillId(skill.getSkillId());
        entity.setAccountId(skill.getAccountId());
        entity.setProjectId(skill.getProjectId());
        entity.setSkillCode(skill.getSkillCode());
        entity.setName(skill.getName());
        entity.setDisplayName(skill.getDisplayName());
        entity.setDescription(skill.getDescription());
        entity.setContent(skill.getContent());
        entity.setCategory(skill.getCategory());
        entity.setTags(String.join(",", skill.getTags() != null ? skill.getTags() : List.of()));
        entity.setUsageCount(skill.getUsageCount() != null ? skill.getUsageCount() : 0);
        entity.setLastUsedAt(skill.getLastUsedAt() != null ? OffsetDateTime.of(skill.getLastUsedAt(), java.time.ZoneOffset.UTC) : null);
        entity.setStatus(skill.getStatus() != null ? skill.getStatus().name() : "ACTIVE");
        entity.setSkillType(skill.getSkillType() != null ? skill.getSkillType().name() : "USER");
        return entity;
    }

    /**
     * 将逗号分隔的标签字符串解析为列表。
     */
    private List<String> parseStringArray(String value) {
        if (value == null || value.isBlank()) return List.of();
        return Arrays.stream(value.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
