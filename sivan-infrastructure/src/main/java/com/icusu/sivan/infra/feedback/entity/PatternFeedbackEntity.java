package com.icusu.sivan.infra.feedback.entity;

import com.icusu.sivan.infra.shared.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

/**
 * pattern_feedback 表 JPA 实体，表示本能模板反馈记录。
 */
@Entity
@Table(name = "pattern_feedback", indexes = {
        @Index(name = "idx_feedback_execution", columnList = "execution_id"),
        @Index(name = "idx_feedback_pattern", columnList = "pattern_id"),
        @Index(name = "idx_feedback_account", columnList = "account_id")
})
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PatternFeedbackEntity extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID feedbackId;

    @Column(name = "pattern_id")
    private UUID patternId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "execution_id", nullable = false)
    private UUID executionId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "actual_features", columnDefinition = "JSONB")
    private String actualFeaturesJson;

    @Column(name = "task_description", columnDefinition = "TEXT")
    private String taskDescription;

    @Column(nullable = false, length = 16)
    private String outcome;

    @Column(name = "outcome_reason", columnDefinition = "TEXT")
    private String outcomeReason;

    @Column(name = "token_cost", nullable = false)
    @Builder.Default
    private int tokenCost = 0;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "JSONB")
    private String deviationJson;

    @Column(nullable = false, length = 32)
    private String source;
}
