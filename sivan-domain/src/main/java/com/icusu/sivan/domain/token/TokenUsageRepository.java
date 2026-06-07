package com.icusu.sivan.domain.token;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Token 用量仓储接口。
 */
public interface TokenUsageRepository {

    /** 保存 Token 用量记录。 */
    UUID save(TokenUsage tokenUsage);

    // ========== 聚合查询 ==========

    /**
     * 统计指定时间后的总 token 用量。
     * 返回 [totalInput, totalOutput] 两元素数组。
     */
    Object[] sumSince(UUID accountId, LocalDateTime since);

    /**
     * 今日趋势：30 分钟粒度，48 个时间点。
     * 返回 [bucketIndex, totalInput, totalOutput] 数组列表。
     */
    List<Object[]> dailyTrend(UUID accountId, LocalDate date);

    /**
     * 按智能体聚合。
     * 返回 [agentId, totalInput, totalOutput] 数组列表。
     */
    List<Object[]> sumByAgentSince(UUID accountId, LocalDateTime since);

    /**
     * 按模型聚合。
     * 返回 [modelName, totalInput, totalOutput] 数组列表。
     */
    List<Object[]> sumByModelSince(UUID accountId, LocalDateTime since);

    // ========== 消耗概览 ==========

    /**
     * 每日 Token 消耗聚合（用于贡献度图）。
     * 返回 [date, totalInput, totalOutput] 数组列表。
     */
    List<Object[]> dailyConsumption(UUID accountId, LocalDateTime since);
}
