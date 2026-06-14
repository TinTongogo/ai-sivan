package com.icusu.sivan.infra.token.entity;

import com.icusu.sivan.infra.shared.entity.BaseCreateOnlyEntity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * token_usage 表 JPA 实体，表示 Token 消耗记录。
 */
@Entity
@Table(name = "token_usage", indexes = {
        @Index(name = "idx_token_account", columnList = "account_id, created_at"),
        @Index(name = "idx_token_agent", columnList = "account_id, agent_id"),
        @Index(name = "idx_token_model", columnList = "account_id, model_name")
})
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TokenUsageEntity extends BaseCreateOnlyEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID tokenUsageId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "project_id")
    private UUID projectId;

    @Column(name = "agent_id")
    private UUID agentId;

    @Column(name = "model_name", nullable = false, length = 64)
    private String modelName;

    @Column(name = "input_tokens", nullable = false)
    @Builder.Default
    private Integer inputTokens = 0;

    @Column(name = "output_tokens", nullable = false)
    @Builder.Default
    private Integer outputTokens = 0;

    @Column(name = "conversation_id")
    private UUID conversationId;

    @Column(nullable = false, length = 32)
    private String source;
}
