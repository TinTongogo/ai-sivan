package com.icusu.sivan.web.routing.dto;

import lombok.Builder;
import lombok.Value;

import java.time.OffsetDateTime;
import java.util.UUID;

@Value
@Builder
public class StrategyPerformanceResponse {
    UUID id;
    String strategy;
    long total;
    long success;
    double avgConfidence;
    double successRate;
    OffsetDateTime updatedAt;
}
