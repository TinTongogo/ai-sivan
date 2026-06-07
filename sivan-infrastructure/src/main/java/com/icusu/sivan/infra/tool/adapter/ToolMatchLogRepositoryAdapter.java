package com.icusu.sivan.infra.tool.adapter;

import com.icusu.sivan.domain.tool.IToolMatchLogRepository;
import com.icusu.sivan.domain.tool.ToolMatchLog;
import com.icusu.sivan.infra.tool.entity.ToolMatchLogEntity;
import com.icusu.sivan.infra.tool.repository.ToolMatchLogJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 工具语义匹配记录仓储适配器。
 */
@Component
@RequiredArgsConstructor
public class ToolMatchLogRepositoryAdapter implements IToolMatchLogRepository {

    private final ToolMatchLogJpaRepository jpaRepository;

    @Override
    public void save(ToolMatchLog log) {
        jpaRepository.save(toEntity(log));
    }

    @Override
    public void saveAll(java.util.List<ToolMatchLog> logs) {
        jpaRepository.saveAll(logs.stream().map(this::toEntity).toList());
    }

    private ToolMatchLogEntity toEntity(ToolMatchLog domain) {
        return ToolMatchLogEntity.builder()
                .accountId(domain.getAccountId())
                .conversationId(domain.getConversationId())
                .toolName(domain.getToolName())
                .serverId(domain.getServerId() != null ? domain.getServerId() : "")
                .similarity(domain.getSimilarity())
                .threshold(domain.getThreshold())
                .passed(domain.getPassed() != null && domain.getPassed())
                .build();
    }
}
