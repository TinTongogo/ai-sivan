package com.icusu.sivan.application.service;

import com.icusu.sivan.common.enums.AgentStatus;
import com.icusu.sivan.common.enums.AgentType;
import com.icusu.sivan.common.exception.DomainException;
import com.icusu.sivan.common.util.OwnershipValidator;
import com.icusu.sivan.domain.agent.AgentDefinition;
import com.icusu.sivan.domain.agent.IAgentRepository;
import com.icusu.sivan.application.agent.dto.CreateAgentRequest;
import com.icusu.sivan.application.agent.dto.UpdateAgentRequest;
import com.icusu.sivan.application.agent.dto.AgentResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 智能体管理服务。 */
@Service
@RequiredArgsConstructor
public class AgentService {

    private final IAgentRepository agentRepository;

    /** 创建智能体。 */
    public AgentResponse create(UUID accountId, CreateAgentRequest request) {
        if (agentRepository.findByAccountAndName(accountId, request.getAgentName()).isPresent()) {
            throw DomainException.conflict("智能体名称已存在");
        }

        AgentDefinition config = AgentDefinition.builder()
                .accountId(accountId)
                .agentName(request.getAgentName())
                .displayName(request.getDisplayName())
                .description(request.getDescription())
                .category(request.getCategory())
                .systemPrompt(request.getSystemPrompt())
                .craftDeclaration(request.getCraftDeclaration())
                .skillIds(request.getSkillIds() != null ? request.getSkillIds() : List.of())
                .toolRequirements(request.getToolRequirements())
                .agentType(AgentType.USER)
                .status(AgentStatus.ACTIVE)
                .version(1)
                .createdBy(accountId.toString())
                .build();

        agentRepository.save(config);
        return toResponse(config);
    }

    /** 根据 ID 查询智能体。 */
    public AgentResponse getById(UUID accountId, UUID agentId) {
        AgentDefinition config = findOwned(accountId, agentId);
        return toResponse(config);
    }

    /** 查询智能体列表。 */
    public List<AgentResponse> list(UUID accountId) {
        return agentRepository.findAllByAccount(accountId).stream()
                .map(this::toResponse).toList();
    }

    /** 更新智能体配置。 */
    public AgentResponse update(UUID accountId, UUID agentId, UpdateAgentRequest request) {
        AgentDefinition config = findOwned(accountId, agentId);
        config.updateFrom(request.getDisplayName(), request.getDescription(), request.getCategory(),
                request.getSystemPrompt(), request.getCraftDeclaration(), request.getSkillIds(),
                request.getToolRequirements(),
                request.getStatus() != null ? AgentStatus.valueOf(request.getStatus()) : null);
        agentRepository.save(config);
        return toResponse(config);
    }

    /** 删除智能体。 */
    public void delete(UUID accountId, UUID agentId) {
        AgentDefinition config = findOwned(accountId, agentId);
        agentRepository.delete(config.getAgentId());
    }

    /** 批量删除智能体（校验所有权后删除）。 */
    @Transactional
    public void deleteBatch(java.util.List<UUID> ids, UUID accountId) {
        if (ids == null || ids.isEmpty()) return;
        for (UUID id : ids) {
            AgentDefinition config = findOwned(accountId, id);
            agentRepository.delete(config.getAgentId());
        }
    }

    /** 获取智能体类型分布统计。 */
    public Map<String, Long> getTypeDistribution(UUID accountId) {
        return agentRepository.countByType(accountId);
    }

    /** 查找当前用户拥有的智能体。 */
    private AgentDefinition findOwned(UUID accountId, UUID agentId) {
        return OwnershipValidator.findOwned(accountId, "智能体", agentId,
                agentRepository::findById, AgentDefinition::getAccountId);
    }

    /** 转换为响应对象。 */
    private AgentResponse toResponse(AgentDefinition config) {
        return AgentResponse.builder()
                .agentId(config.getAgentId())
                .agentName(config.getAgentName())
                .displayName(config.getDisplayName())
                .description(config.getDescription())
                .category(config.getCategory())
                .systemPrompt(config.getSystemPrompt())
                .craftDeclaration(config.getCraftDeclaration())
                .skillIds(config.getSkillIds())
                .toolRequirements(config.getToolRequirements())
                .agentType(config.getAgentType() != null ? config.getAgentType().name() : null)
                .status(config.getStatus() != null ? config.getStatus().name() : null)
                .version(config.getVersion())
                .usageCount(config.getUsageCount())
                .lastUsedAt(config.getLastUsedAt())
                .createdAt(config.getCreatedAt())
                .updatedAt(config.getUpdatedAt())
                .build();
    }
}
