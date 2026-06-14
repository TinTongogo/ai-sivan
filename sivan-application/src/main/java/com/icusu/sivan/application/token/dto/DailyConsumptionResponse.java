package com.icusu.sivan.application.token.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

/**
 * 每日 Token 消耗响应（贡献度图数据点）。
 */
@Data
@Builder
@AllArgsConstructor
public class DailyConsumptionResponse {
    private String date;
    private long totalInput;
    private long totalOutput;
    private long totalTokens;
    private int level;
}
