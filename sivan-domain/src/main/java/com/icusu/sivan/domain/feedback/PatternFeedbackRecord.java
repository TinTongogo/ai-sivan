package com.icusu.sivan.domain.feedback;

import com.icusu.sivan.domain.task.TaskFeatures;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 本能模板反馈记录。采集 LLM 执行结果与模板的偏差，驱动模板自动优化。
 */
public class PatternFeedbackRecord {

    private UUID feedbackId;
    private UUID patternId;           // 命中的模板 ID（可为 null）
    private UUID accountId;
    private UUID executionId;

    private TaskFeatures actualFeatures;
    private String taskDescription;

    private FeedbackOutcome outcome;
    private String outcomeReason;
    private int tokenCost;

    private FeatureDeviation deviation;

    private String source;            // TRIGGER_LLM / TRIGGER_PATTERN / TRIGGER_MANUAL
    private LocalDateTime createdAt;

    // ===== 构造 =====

    public PatternFeedbackRecord() {}

    @lombok.Builder
    public PatternFeedbackRecord(UUID feedbackId, UUID patternId, UUID accountId,
                                  UUID executionId, TaskFeatures actualFeatures,
                                  String taskDescription, FeedbackOutcome outcome,
                                  String outcomeReason, int tokenCost,
                                  FeatureDeviation deviation, String source,
                                  LocalDateTime createdAt) {
        this.feedbackId = feedbackId;
        this.patternId = patternId;
        this.accountId = accountId;
        this.executionId = executionId;
        this.actualFeatures = actualFeatures;
        this.taskDescription = taskDescription;
        this.outcome = outcome;
        this.outcomeReason = outcomeReason;
        this.tokenCost = tokenCost;
        this.deviation = deviation;
        this.source = source;
        this.createdAt = createdAt;
    }

    // ===== Getters / Setters =====

    public UUID getFeedbackId() { return feedbackId; }
    public void setFeedbackId(UUID feedbackId) { this.feedbackId = feedbackId; }

    public UUID getPatternId() { return patternId; }
    public void setPatternId(UUID patternId) { this.patternId = patternId; }

    public UUID getAccountId() { return accountId; }
    public void setAccountId(UUID accountId) { this.accountId = accountId; }

    public UUID getExecutionId() { return executionId; }
    public void setExecutionId(UUID executionId) { this.executionId = executionId; }

    public TaskFeatures getActualFeatures() { return actualFeatures; }
    public void setActualFeatures(TaskFeatures actualFeatures) { this.actualFeatures = actualFeatures; }

    public String getTaskDescription() { return taskDescription; }
    public void setTaskDescription(String taskDescription) { this.taskDescription = taskDescription; }

    public FeedbackOutcome getOutcome() { return outcome; }
    public void setOutcome(FeedbackOutcome outcome) { this.outcome = outcome; }

    public String getOutcomeReason() { return outcomeReason; }
    public void setOutcomeReason(String outcomeReason) { this.outcomeReason = outcomeReason; }

    public int getTokenCost() { return tokenCost; }
    public void setTokenCost(int tokenCost) { this.tokenCost = tokenCost; }

    public FeatureDeviation getDeviation() { return deviation; }
    public void setDeviation(FeatureDeviation deviation) { this.deviation = deviation; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    // ===== 枚举 =====

    public enum FeedbackOutcome {
        SUCCESS,
        FAILURE,
        PARTIAL
    }

    public enum FeedbackSource {
        TRIGGER_LLM,
        TRIGGER_PATTERN,
        TRIGGER_MANUAL
    }
}
