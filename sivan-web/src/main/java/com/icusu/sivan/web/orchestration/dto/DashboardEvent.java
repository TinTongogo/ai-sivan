package com.icusu.sivan.web.orchestration.dto;

import lombok.Builder;
import lombok.Value;

import java.util.List;

/** 仪表盘 SSE 事件，包含统计数据和执行列表快照。 */
@Value
@Builder
public class DashboardEvent {
    ExecutionStatsResponse stats;
    List<SquadExecutionResponse> executions;
    long totalCount;
}
