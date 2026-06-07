package com.icusu.sivan.web.orchestration.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

/** Squad 执行统计响应。 */
@Data
@Builder
@AllArgsConstructor
public class ExecutionStatsResponse {
    private long running;
    private long hitlWaiting;
    private long todayDone;
    private long todayFailed;
}
