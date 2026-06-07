package com.icusu.sivan.domain.routing;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 路由策略执行效果统计，按 (accountId, strategy) 聚合。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StrategyPerformance {
    private UUID id;
    private UUID accountId;
    private String strategy;
    private long total;
    private long success;
    private double sumConfidence;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
