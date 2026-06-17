package com.icusu.sivan.infra.forest.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 意图分类反馈日志 JPA 实体。
 */
@Entity
@Table(name = "intent_feedback_log")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IntentFeedbackLogEntity {

    @Id
    @Column(name = "log_id")
    private UUID logId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "conversation_id")
    private UUID conversationId;

    @Column(name = "message_id")
    private UUID messageId;

    @Column(name = "message_text", nullable = false)
    private String messageText;

    @Column(name = "predicted_intent", nullable = false, length = 16)
    private String predictedIntent;

    @Column(name = "confidence")
    private Double confidence;

    @Column(name = "user_correction", length = 16)
    private String userCorrection;

    @Column(name = "corrected_intent", length = 16)
    private String correctedIntent;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (logId == null) logId = UUID.randomUUID();
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }
}
