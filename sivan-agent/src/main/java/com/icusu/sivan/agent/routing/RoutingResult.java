package com.icusu.sivan.agent.routing;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

/**
 * 路由策略输出结果。
 */
@Data
@Builder
@AllArgsConstructor
public class RoutingResult {
    private String selectedAgent;
    private double confidence;
    private String reasoning;
    private String strategyName;
    private String errorDetail;
}
