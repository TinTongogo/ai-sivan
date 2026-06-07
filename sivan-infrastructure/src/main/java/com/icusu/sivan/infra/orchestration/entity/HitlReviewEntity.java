package com.icusu.sivan.infra.orchestration.entity;

import com.icusu.sivan.infra.shared.entity.BaseEntity;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * hitl_reviews 表 JPA 实体，表示人工审核（Human-in-the-Loop）记录。
 */
@Entity
@Table(name = "hitl_reviews")
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HitlReviewEntity extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID reviewId;

    @Column(name = "execution_id", nullable = false)
    private UUID executionId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(nullable = false)
    private Integer phase;

    @Column(name = "phase_name", length = 128)
    private String phaseName;

    @Column(name = "input_content", columnDefinition = "TEXT")
    private String inputContent;

    @Column(name = "output_content", columnDefinition = "TEXT")
    private String outputContent;

    @Column(name = "human_feedback", columnDefinition = "TEXT")
    private String humanFeedback;

    @Column(nullable = false, length = 16)
    @Builder.Default
    private String status = "PENDING";

    @Column(name = "expires_at")
    private OffsetDateTime expiresAt;

    /** CORRECT 操作时注入的修正内容。 */
    @Column(name = "corrected_content", columnDefinition = "TEXT")
    private String correctedContent;

    /** RESTART_PHASE / RESTART_AGENT 时的修正提示。 */
    @Column(name = "restart_hint", columnDefinition = "TEXT")
    private String restartHint;

    /** RESTART_AGENT 时指定的 Agent 名称。 */
    @Column(name = "restart_agent", length = 128)
    private String restartAgent;
}
