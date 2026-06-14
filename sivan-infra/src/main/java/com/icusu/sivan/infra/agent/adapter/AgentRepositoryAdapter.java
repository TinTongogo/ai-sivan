package com.icusu.sivan.infra.agent.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.icusu.sivan.common.enums.AgentStatus;
import com.icusu.sivan.common.enums.AgentType;
import com.icusu.sivan.domain.agent.AgentDefinition;
import com.icusu.sivan.domain.agent.IAgentRepository;
import com.icusu.sivan.domain.tool.ToolRequirement;
import com.icusu.sivan.infra.agent.entity.AgentEntity;
import com.icusu.sivan.infra.agent.repository.AgentJpaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 智能体仓储适配器，实现 IAgentRepository。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AgentRepositoryAdapter implements IAgentRepository {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .findAndRegisterModules();

    private final AgentJpaRepository jpaRepository;

    /**
     * 根据 ID 查询智能体配置。
     */
    @Override
    public Optional<AgentDefinition> findById(UUID agentId) {
        return jpaRepository.findById(agentId).map(this::toDomain);
    }

    /**
     * 根据账号和名称查询智能体。
     */
    @Override
    public Optional<AgentDefinition> findByAccountAndName(UUID accountId, String agentName) {
        return jpaRepository.findByAccountIdAndAgentName(accountId, agentName).map(this::toDomain);
    }

    /**
     * 查询账号下所有智能体。
     */
    @Override
    public List<AgentDefinition> findAllByAccount(UUID accountId) {
        return jpaRepository.findByAccountId(accountId).stream().map(this::toDomain).toList();
    }

    /**
     * 保存智能体配置，回写 ID、版本和时间戳。
     */
    @Override
    public void save(AgentDefinition config) {
        AgentEntity entity;
        if (config.getAgentId() != null) {
            // 更新：保留现有 optlock（JPA 乐观锁），防止 OptimisticLockException
            entity = jpaRepository.findById(config.getAgentId())
                    .map(existing -> {
                        AgentEntity updated = toEntity(config);
                        updated.setOptlock(existing.getOptlock());
                        return updated;
                    })
                    .orElse(toEntity(config));
        } else {
            entity = toEntity(config);
        }
        entity = jpaRepository.save(entity);
        jpaRepository.flush();
        if (config.getAgentId() == null) {
            config.setAgentId(entity.getAgentId());
        }
        config.setVersion(entity.getVersion());
        config.setCreatedAt(entity.getCreatedAt() != null ? entity.getCreatedAt().toLocalDateTime() : null);
        config.setUpdatedAt(entity.getUpdatedAt() != null ? entity.getUpdatedAt().toLocalDateTime() : null);
    }

    /**
     * 根据 ID 删除智能体。
     */
    @Override
    public void delete(UUID agentId) {
        jpaRepository.deleteById(agentId);
    }

    @Override
    public void deleteBatch(java.util.List<UUID> ids) {
        if (ids != null && !ids.isEmpty()) jpaRepository.deleteAllById(ids);
    }

    /**
     * 检查是否存在同名智能体（排除指定 ID）。
     */
    @Override
    public boolean existsByNameExcludingId(UUID accountId, String agentName, UUID excludeId) {
        return jpaRepository.existsByAccountIdAndAgentNameAndAgentIdNot(accountId, agentName, excludeId);
    }

    /**
     * 按类型统计智能体数量。
     */
    @Override
    public Map<String, Long> countByType(UUID accountId) {
        return jpaRepository.findByAccountId(accountId).stream()
                .collect(Collectors.groupingBy(
                        e -> e.getAgentType() != null ? e.getAgentType() : "USER",
                        Collectors.counting()));
    }

    /**
     * 统计账号下智能体总数。
     */
    @Override
    public long countByAccount(UUID accountId) {
        return jpaRepository.countByAccountId(accountId);
    }

    /**
     * 将实体转换为领域对象。
     */
    private AgentDefinition toDomain(AgentEntity entity) {
        if (entity.getAgentType() == null) {
            log.warn("智能体 agentType 为空，使用默认值 USER: agentId={}, agentName={}", entity.getAgentId(), entity.getAgentName());
        }
        return AgentDefinition.builder()
                .agentId(entity.getAgentId())
                .accountId(entity.getAccountId())
                .agentName(entity.getAgentName())
                .displayName(entity.getDisplayName())
                .description(entity.getDescription())
                .category(entity.getCategory())
                .systemPrompt(entity.getSystemPrompt())
                .craftDeclaration(entity.getCraftDeclaration())
                .skillIds(parseStringArray(entity.getSkillIds()))
                .agentType(entity.getAgentType() != null ? AgentType.valueOf(entity.getAgentType()) : AgentType.USER)
                .status(entity.getStatus() != null ? AgentStatus.valueOf(entity.getStatus()) : AgentStatus.ACTIVE)
                .version(entity.getVersion())
                .createdBy(entity.getCreatedBy())
                .usageCount(entity.getUsageCount())
                .lastUsedAt(entity.getLastUsedAt() != null ? entity.getLastUsedAt().toLocalDateTime() : null)
                .toolRequirements(parseToolRequirements(entity.getToolRequirements()))
                .createdAt(entity.getCreatedAt() != null ? entity.getCreatedAt().toLocalDateTime() : null)
                .updatedAt(entity.getUpdatedAt() != null ? entity.getUpdatedAt().toLocalDateTime() : null)
                .build();
    }

    /**
     * 将领域对象转换为实体。
     */
    private AgentEntity toEntity(AgentDefinition config) {
        AgentEntity entity = new AgentEntity();
        entity.setAgentId(config.getAgentId());
        entity.setAccountId(config.getAccountId());
        entity.setAgentName(config.getAgentName());
        entity.setDisplayName(config.getDisplayName());
        entity.setDescription(config.getDescription());
        entity.setCategory(config.getCategory());
        entity.setSystemPrompt(config.getSystemPrompt());
        entity.setCraftDeclaration(config.getCraftDeclaration());
        entity.setSkillIds(String.join(",", config.getSkillIds() != null ? config.getSkillIds() : List.of()));
        entity.setAgentType(config.getAgentType() != null ? config.getAgentType().name() : "USER");
        entity.setStatus(config.getStatus() != null ? config.getStatus().name() : "ACTIVE");
        entity.setVersion(config.getVersion() != null ? config.getVersion() : 1);
        entity.setCreatedBy(config.getCreatedBy());
        entity.setUsageCount(config.getUsageCount() != null ? config.getUsageCount() : 0);
        entity.setLastUsedAt(config.getLastUsedAt() != null ? OffsetDateTime.of(config.getLastUsedAt(), java.time.ZoneOffset.UTC) : null);
        entity.setToolRequirements(toJsonString(config.getToolRequirements()));
        return entity;
    }

    /**
     * 解析逗号分隔的字符串为列表。
     */
    private List<String> parseStringArray(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /** 将 JSON 字符串解析为 ToolRequirement，失败时返回默认配置。 */
    private ToolRequirement parseToolRequirements(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readValue(json, ToolRequirement.class);
        } catch (Exception e) {
            log.warn("解析 tool_requirements JSON 失败", e);
            return null;
        }
    }

    /** 将 ToolRequirement 序列化为 JSON 字符串。 */
    private String toJsonString(ToolRequirement req) {
        if (req == null) {
            return null;
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(req);
        } catch (Exception e) {
            log.warn("序列化 tool_requirements 失败", e);
            return null;
        }
    }
}
