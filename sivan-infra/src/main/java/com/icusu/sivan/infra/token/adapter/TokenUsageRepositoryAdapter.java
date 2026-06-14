package com.icusu.sivan.infra.token.adapter;

import com.icusu.sivan.domain.token.TokenUsage;
import com.icusu.sivan.domain.token.TokenUsageRepository;
import com.icusu.sivan.infra.token.entity.TokenUsageEntity;
import com.icusu.sivan.infra.token.repository.TokenUsageJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Token 用量仓储适配器，实现 ITokenUsageRepository。
 */
@Component
@RequiredArgsConstructor
public class TokenUsageRepositoryAdapter implements TokenUsageRepository {

    private final TokenUsageJpaRepository jpaRepository;

    /**
     * 保存 Token 用量记录。
     */
    @Override
    public UUID save(TokenUsage domain) {
        TokenUsageEntity entity = toEntity(domain);
        jpaRepository.save(entity);
        return entity.getTokenUsageId();
    }

    /**
     * 统计指定时间后的 Token 总量。
     */
    @Override
    public Object[] sumSince(UUID accountId, LocalDateTime since) {
        List<Object[]> results = jpaRepository.sumSince(accountId, since);
        return results.isEmpty() ? new Object[]{0L, 0L} : results.getFirst();
    }

    /**
     * 查询每日 Token 用量趋势。
     */
    @Override
    public List<Object[]> dailyTrend(UUID accountId, LocalDate date) {
        return jpaRepository.dailyTrend(accountId, date);
    }

    /**
     * 按智能体统计 Token 用量。
     */
    @Override
    public List<Object[]> sumByAgentSince(UUID accountId, LocalDateTime since) {
        return jpaRepository.sumByAgentSince(accountId, since);
    }

    /**
     * 按模型统计 Token 用量。
     */
    @Override
    public List<Object[]> sumByModelSince(UUID accountId, LocalDateTime since) {
        return jpaRepository.sumByModelSince(accountId, since);
    }

    @Override
    public List<Object[]> dailyConsumption(UUID accountId, LocalDateTime since) {
        return jpaRepository.dailyConsumption(accountId, since);
    }

    /**
     * 将领域对象转换为实体。
     */
    private TokenUsageEntity toEntity(TokenUsage domain) {
        TokenUsageEntity entity = new TokenUsageEntity();
        entity.setTokenUsageId(domain.getTokenUsageId());
        entity.setAccountId(domain.getAccountId());
        entity.setProjectId(domain.getProjectId());
        entity.setAgentId(domain.getAgentId());
        entity.setModelName(domain.getModelName());
        entity.setInputTokens(domain.getInputTokens());
        entity.setOutputTokens(domain.getOutputTokens());
        entity.setConversationId(domain.getConversationId());
        entity.setSource(domain.getSource() != null ? domain.getSource().name() : null);
        return entity;
    }

}
