package com.icusu.sivan.domain.routing;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
* 路由决策记录实体。
*/
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoutingDecision {

    private UUID decisionId;
    private UUID accountId;
    private UUID projectId;
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
