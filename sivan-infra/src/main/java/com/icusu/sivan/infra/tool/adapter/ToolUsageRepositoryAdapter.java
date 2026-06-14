package com.icusu.sivan.infra.tool.adapter;

import com.icusu.sivan.domain.tool.IToolUsageRepository;
import com.icusu.sivan.domain.tool.ToolUsage;
import com.icusu.sivan.infra.tool.entity.ToolUsageEntity;
import com.icusu.sivan.infra.tool.repository.ToolUsageJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * 工具使用记录仓储适配器。
 */
@Component
@RequiredArgsConstructor
public class ToolUsageRepositoryAdapter implements IToolUsageRepository {

    private final ToolUsageJpaRepository jpaRepository;

    @Override
    public void save(ToolUsage toolUsage) {
        ToolUsageEntity entity = toEntity(toolUsage);
        jpaRepository.save(entity);
    }

    @Override
    public List<Object[]> countByToolName(UUID accountId) {
        return jpaRepository.countByToolName(accountId);
    }

    @Override
    public List<Object[]> countByToolNameAndAgent(UUID accountId, String agentName) {
        return jpaRepository.countByToolNameAndAgent(accountId, agentName);
    }

    private ToolUsageEntity toEntity(ToolUsage domain) {
        return ToolUsageEntity.builder()
                .accountId(domain.getAccountId())
                .agentName(domain.getAgentName())
                .toolName(domain.getToolName())
                .serverId(domain.getServerId() != null ? domain.getServerId() : "")
                .success(domain.isSuccess())
                .durationMs(domain.getDurationMs())
                .conversationId(domain.getConversationId())
                .build();
    }
}
