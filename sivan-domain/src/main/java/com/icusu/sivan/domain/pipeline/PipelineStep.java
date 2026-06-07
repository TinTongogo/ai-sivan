package com.icusu.sivan.domain.pipeline;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * 编排流水线步骤 — 记录一条消息的编排执行链路的单个步骤。
 * <p>
 * 涵盖从意图分类、历史压缩、Agent 匹配到每个阶段执行的全部步骤。
 * parentStepId 支持层次结构：squad → phases → agent 输出。
 */
public class PipelineStep {

    private UUID stepId;
    private UUID messageId;
    private UUID routingDecisionId;
    private UUID executionId;
    private String stepType;
    private String stepName;
    private String status;
    private int sequence;
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

    public PipelineStep() {}

    @lombok.Builder
    public PipelineStep(UUID stepId, UUID messageId, UUID routingDecisionId, UUID executionId,
                        String stepType, String stepName, String status, int sequence,
                        UUID parentStepId, OffsetDateTime startedAt, OffsetDateTime completedAt,
                        Integer durationMs, String inputSummary, String outputSummary,
                        String agentName, String modelName, Integer tokenCount,
                        Map<String, Object> metadataJson, OffsetDateTime createdAt) {
        this.stepId = stepId;
        this.messageId = messageId;
        this.routingDecisionId = routingDecisionId;
        this.executionId = executionId;
        this.stepType = stepType;
        this.stepName = stepName;
        this.status = status;
        this.sequence = sequence;
        this.parentStepId = parentStepId;
        this.startedAt = startedAt;
        this.completedAt = completedAt;
        this.durationMs = durationMs;
        this.inputSummary = inputSummary;
        this.outputSummary = outputSummary;
        this.agentName = agentName;
        this.modelName = modelName;
        this.tokenCount = tokenCount;
        this.metadataJson = metadataJson;
        this.createdAt = createdAt;
    }

    // --- getters ---

    public UUID getStepId() { return stepId; }
    public UUID getMessageId() { return messageId; }
    public UUID getRoutingDecisionId() { return routingDecisionId; }
    public UUID getExecutionId() { return executionId; }
    public String getStepType() { return stepType; }
    public String getStepName() { return stepName; }
    public String getStatus() { return status; }
    public int getSequence() { return sequence; }
    public UUID getParentStepId() { return parentStepId; }
    public OffsetDateTime getStartedAt() { return startedAt; }
    public OffsetDateTime getCompletedAt() { return completedAt; }
    public Integer getDurationMs() { return durationMs; }
    public String getInputSummary() { return inputSummary; }
    public String getOutputSummary() { return outputSummary; }
    public String getAgentName() { return agentName; }
    public String getModelName() { return modelName; }
    public Integer getTokenCount() { return tokenCount; }
    public Map<String, Object> getMetadataJson() { return metadataJson; }
    public OffsetDateTime getCreatedAt() { return createdAt; }

    // --- setters ---

    public void setStepId(UUID stepId) { this.stepId = stepId; }
    public void setMessageId(UUID messageId) { this.messageId = messageId; }
    public void setRoutingDecisionId(UUID routingDecisionId) { this.routingDecisionId = routingDecisionId; }
    public void setExecutionId(UUID executionId) { this.executionId = executionId; }
    public void setStepType(String stepType) { this.stepType = stepType; }
    public void setStepName(String stepName) { this.stepName = stepName; }
    public void setStatus(String status) { this.status = status; }
    public void setSequence(int sequence) { this.sequence = sequence; }
    public void setParentStepId(UUID parentStepId) { this.parentStepId = parentStepId; }
    public void setStartedAt(OffsetDateTime startedAt) { this.startedAt = startedAt; }
    public void setCompletedAt(OffsetDateTime completedAt) { this.completedAt = completedAt; }
    public void setDurationMs(Integer durationMs) { this.durationMs = durationMs; }
    public void setInputSummary(String inputSummary) { this.inputSummary = inputSummary; }
    public void setOutputSummary(String outputSummary) { this.outputSummary = outputSummary; }
    public void setAgentName(String agentName) { this.agentName = agentName; }
    public void setModelName(String modelName) { this.modelName = modelName; }
    public void setTokenCount(Integer tokenCount) { this.tokenCount = tokenCount; }
    public void setMetadataJson(Map<String, Object> metadataJson) { this.metadataJson = metadataJson; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
