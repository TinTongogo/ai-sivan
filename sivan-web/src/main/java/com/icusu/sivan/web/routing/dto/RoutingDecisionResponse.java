package com.icusu.sivan.web.routing.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * 路由决策响应 DTO。
 */
@Data
@Builder
@AllArgsConstructor
public class RoutingDecisionResponse {
    private UUID decisionId;
    private UUID conversationId;
    private String taskDescription;
    private String selectedAgentName;
    private String strategy;
    private Boolean success;
    private Double confidence;
    private Map<String, Object> context;
    private String errorHint;
    private String reasoning;
    private LocalDateTime createdAt;
}
