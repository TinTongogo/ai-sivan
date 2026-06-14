package com.icusu.sivan.application.token.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Token 用量汇总响应。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TokenUsageSummaryResponse {
    private PeriodSummary today;
    private PeriodSummary last7Days;
    private PeriodSummary last30Days;
    private PeriodSummary last90Days;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PeriodSummary {
        private long totalInput;
        private long totalOutput;
        private long totalTokens;
    }
}
