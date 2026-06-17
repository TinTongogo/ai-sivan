package com.icusu.sivan.infra.routing.entity;

import com.icusu.sivan.infra.knowledge.entity.FloatArrayVectorType;
import com.icusu.sivan.infra.shared.entity.BaseCreateOnlyEntity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnTransformer;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.Type;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

/**
 * routing_decisions 表 JPA 实体，表示智能体路由决策记录。
 */
@Entity
@Table(name = "routing_decisions")
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RoutingDecisionEntity extends BaseCreateOnlyEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID decisionId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "project_id")
    private UUID projectId;

    @Column(name = "conversation_id")
    private UUID conversationId;

    @Column(name = "task_description", columnDefinition = "TEXT")
    private String taskDescription;

    @Column(name = "selected_agent", length = 64)
    private String selectedAgent;

    @Column(length = 32)
    private String strategy;

    @Column(nullable = false)
    private Boolean success;

    private Double confidence;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "JSONB")
    private String contextJson;

    @Column(name = "error_hint", columnDefinition = "TEXT")
    private String errorHint;

    @Column(columnDefinition = "TEXT")
    private String reasoning;

    /** 任务 embedding 向量（1024 维，pgvector）。 */
    @Type(FloatArrayVectorType.class)
    @ColumnTransformer(write = "?::vector")
    @Column(name = "task_embedding", columnDefinition = "vector(1024)")
    private float[] taskEmbedding;

    @Column(name = "duration_ms")
    private Integer durationMs;
}
