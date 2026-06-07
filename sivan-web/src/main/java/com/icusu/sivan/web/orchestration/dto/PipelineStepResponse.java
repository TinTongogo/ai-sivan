package com.icusu.sivan.web.orchestration.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 编排流水线步骤响应 DTO。
 */
@Data
@Builder
@AllArgsConstructor
public class PipelineStepResponse {
    private UUID stepId;
    private UUID messageId;
    private UUID routingDecisionId;
    private UUID executionId;
    private String stepType;
    private String stepName;
    private String status;
    private Integer sequence;
    private UUID parentStepId;
    private OffsetDateTime startedAt;
    private OffsetDateTime completedAt;
    private Integer durationMs;
    private String inputSummary;
    private String outputSummary;
    private String agentName;
    private String modelName;
    private Integer tokenCount;
    private Map<String, Object> metadataJson;
    private OffsetDateTime createdAt;
    /** 子步骤（递归填充）。 */
    private List<PipelineStepResponse> children;
}
