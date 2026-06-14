package com.icusu.sivan.application.token;

import com.icusu.sivan.domain.agent.IAgentRepository;
import com.icusu.sivan.domain.token.TokenUsageRepository;
import com.icusu.sivan.application.model.dto.ModelTokenSummary;
import com.icusu.sivan.application.token.dto.AgentTokenSummary;
import com.icusu.sivan.application.token.dto.DailyConsumptionResponse;
import com.icusu.sivan.application.token.dto.TokenUsageSummaryResponse;
import com.icusu.sivan.application.token.dto.TrendPoint;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** Token 用量统计服务。 */
@Service
@RequiredArgsConstructor
public class TokenUsageService {

    private final TokenUsageRepository tokenUsageRepository;
    private final IAgentRepository agentRepository;

    /** 获取 Token 用量汇总（今日/7天/30天/90天）。 */
    public TokenUsageSummaryResponse getSummary(UUID accountId) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        LocalDate today = now.toLocalDate();

        return TokenUsageSummaryResponse.builder()
                .today(toPeriodSummary(tokenUsageRepository.sumSince(accountId, today.atStartOfDay())))
                .last7Days(toPeriodSummary(tokenUsageRepository.sumSince(accountId, now.minusDays(7))))
                .last30Days(toPeriodSummary(tokenUsageRepository.sumSince(accountId, now.minusDays(30))))
                .last90Days(toPeriodSummary(tokenUsageRepository.sumSince(accountId, now.minusDays(90))))
                .build();
    }

    /** 获取每日 Token 消耗趋势。 */
    public List<TrendPoint> getDailyTrend(UUID accountId) {
        List<Object[]> rows = tokenUsageRepository.dailyTrend(accountId, LocalDate.now(ZoneOffset.UTC));
        return rows.stream().map(row -> TrendPoint.builder()
                .bucket(((Number) row[0]).intValue())
                .totalInput(((Number) row[1]).longValue())
                .totalOutput(((Number) row[2]).longValue())
                .build()).collect(Collectors.toList());
    }

    /** 按智能体统计 Token 用量。 */
    public List<AgentTokenSummary> getByAgent(UUID accountId) {
        // 预加载所有 agent 的名称映射
        Map<UUID, String> nameMap = agentRepository.findAllByAccount(accountId).stream()
                .collect(Collectors.toMap(
                        a -> a.getAgentId(),
                        a -> a.getDisplayName() != null ? a.getDisplayName() : a.getAgentName(),
                        (a, b) -> a));
        List<Object[]> rows = tokenUsageRepository.sumByAgentSince(accountId, LocalDateTime.now(ZoneOffset.UTC).minusDays(30));
        return rows.stream().map(row -> {
            UUID agentId = row[0] != null ? UUID.fromString(row[0].toString()) : null;
            return AgentTokenSummary.builder()
                    .agentId(agentId)
                    .agentName(agentId != null ? nameMap.getOrDefault(agentId, null) : null)
                    .totalInput(((Number) row[1]).longValue())
                    .totalOutput(((Number) row[2]).longValue())
                    .totalTokens(((Number) row[1]).longValue() + ((Number) row[2]).longValue())
                    .build();
        }).collect(Collectors.toList());
    }

    /** 按模型统计 Token 用量。 */
    public List<ModelTokenSummary> getByModel(UUID accountId) {
        List<Object[]> rows = tokenUsageRepository.sumByModelSince(accountId, LocalDateTime.now(ZoneOffset.UTC).minusDays(30));
        return rows.stream().map(row -> ModelTokenSummary.builder()
                .modelName((String) row[0])
                .totalInput(((Number) row[1]).longValue())
                .totalOutput(((Number) row[2]).longValue())
                .totalTokens(((Number) row[1]).longValue() + ((Number) row[2]).longValue())
                .build()).collect(Collectors.toList());
    }

    /** 获取每日 Token 消耗概览（贡献度图）。 */
    public List<DailyConsumptionResponse> getDailyConsumption(UUID accountId, int days) {
        LocalDateTime since = LocalDateTime.now(ZoneOffset.UTC).minusDays(days);
        List<Object[]> rows = tokenUsageRepository.dailyConsumption(accountId, since);

        long maxTokens = rows.stream()
                .mapToLong(r -> ((Number) r[1]).longValue() + ((Number) r[2]).longValue())
                .max().orElse(0);

        double step = maxTokens > 0 ? maxTokens / 4.0 : 1;
        return rows.stream().map(row -> {
            long input = ((Number) row[1]).longValue();
            long output = ((Number) row[2]).longValue();
            long total = input + output;
            int level = total > 0 ? (int) Math.min(4, Math.ceil(total / step)) : 0;
            return DailyConsumptionResponse.builder()
                    .date(row[0].toString())
                    .totalInput(input)
                    .totalOutput(output)
                    .totalTokens(total)
                    .level(level)
                    .build();
        }).collect(Collectors.toList());
    }

    /** 数据库统计行转为周期汇总对象。 */
    private TokenUsageSummaryResponse.PeriodSummary toPeriodSummary(Object[] row) {
        long totalInput = row != null ? ((Number) row[0]).longValue() : 0L;
        long totalOutput = row != null ? ((Number) row[1]).longValue() : 0L;
        return TokenUsageSummaryResponse.PeriodSummary.builder()
                .totalInput(totalInput)
                .totalOutput(totalOutput)
                .totalTokens(totalInput + totalOutput)
                .build();
    }
}
