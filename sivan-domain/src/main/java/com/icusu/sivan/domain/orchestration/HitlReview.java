package com.icusu.sivan.domain.orchestration;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * HITL（Human-In-The-Loop）人工审核记录。
 * 当 Squad 执行到需要人工审核的阶段时，创建此记录并暂停执行。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HitlReview {

    private UUID reviewId;
    private UUID executionId;
    private UUID accountId;
    private Integer phase;
    private String phaseName;
    private String inputContent;
    private String outputContent;
    private String humanFeedback;
    private String status;       // PENDING / APPROVED / REJECTED / TIMEOUT / CORRECTED / RESTART_PHASE / RESTART_AGENT
    private LocalDateTime expiresAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /** CORRECT 操作时注入的修正内容。 */
    private String correctedContent;
    /** RESTART_PHASE / RESTART_AGENT 时的修正提示。 */
    private String restartHint;
    /** RESTART_AGENT 时指定的 Agent 名称。 */
    private String restartAgent;

    /** 默认审核等待时长（分钟）。 */
    public static final long DEFAULT_TIMEOUT_MINUTES = 5;

    public void approve(String feedback) {
        this.status = "APPROVED";
        this.humanFeedback = feedback;
        this.updatedAt = LocalDateTime.now();
    }

    public void reject(String feedback) {
        this.status = "REJECTED";
        this.humanFeedback = feedback;
        this.updatedAt = LocalDateTime.now();
    }

    public void timeout() {
        this.status = "TIMEOUT";
        this.updatedAt = LocalDateTime.now();
    }

    public void correct(String correctedContent) {
        this.status = "CORRECTED";
        this.correctedContent = correctedContent;
        this.updatedAt = LocalDateTime.now();
    }

    public void restartPhase(String hint) {
        this.status = "RESTART_PHASE";
        this.restartHint = hint;
        this.updatedAt = LocalDateTime.now();
    }

    public void restartAgent(String agentName, String hint) {
        this.status = "RESTART_AGENT";
        this.restartAgent = agentName;
        this.restartHint = hint;
        this.updatedAt = LocalDateTime.now();
    }

    public boolean isExpired() {
        return this.expiresAt != null && LocalDateTime.now().isAfter(this.expiresAt);
    }

    public boolean isPending() {
        return "PENDING".equals(this.status);
    }

    public boolean isCorrected() {
        return "CORRECTED".equals(this.status);
    }

    public boolean isRestartPhase() {
        return "RESTART_PHASE".equals(this.status);
    }

    public boolean isRestartAgent() {
        return "RESTART_AGENT".equals(this.status);
    }
}
