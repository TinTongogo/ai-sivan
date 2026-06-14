package com.icusu.sivan.infra.conversation.entity;

import com.icusu.sivan.infra.shared.entity.BaseCreateOnlyEntity;

import jakarta.persistence.*;
import lombok.*;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

/**
 * messages 表 JPA 实体，表示对话中的消息。
 */
@Entity
@Table(name = "messages", indexes = {
        @Index(name = "idx_message_conversation", columnList = "conversation_id")
}, uniqueConstraints = {
        @UniqueConstraint(name = "uq_message_conversation_sort", columnNames = {"conversation_id", "sort_order"})
})
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MessageEntity extends BaseCreateOnlyEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID messageId;

    @Column(name = "conversation_id", nullable = false)
    private UUID conversationId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "project_id")
    private UUID projectId;

    @Column(length = 16)
    private String role;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(columnDefinition = "TEXT")
    private String thinking;

    @Column(name = "content_type", length = 32)
    @Builder.Default
    private String contentType = "text";

    @Column(name = "target_agent", length = 64)
    private String targetAgent;

    @Column(name = "reply_to_id")
    private UUID replyToId;

    @Column(name = "sort_order")
    private Integer sortOrder;

    @Column(length = 16)
    @Builder.Default
    private String status = "COMPLETED";

    @Column(length = 16)
    private String rating;

    @Column(length = 100)
    private String model;

    @Column(name = "total_tokens")
    private Integer totalTokens;

    @Column(name = "duration_ms")
    private Integer durationMs;

    @Column(name = "thinking_duration_ms")
    private Integer thinkingDurationMs;

    @Column(name = "thinking_tokens")
    private Integer thinkingTokens;

    @Column(length = 64)
    private String chain;

    @Column(columnDefinition = "TEXT")
    private String images;

    @Column(columnDefinition = "TEXT")
    private String attachments;

    @Column(name = "generation_index", nullable = false)
    @Builder.Default
    private Integer generationIndex = 1;

    @Column(name = "generation_group")
    private UUID generationGroup;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "JSONB")
    private String sections;

    /** msg_type: 'normal' / 'goal_start' / 'goal_end' / 'summary' */
    @Column(name = "msg_type", length = 16)
    @Builder.Default
    private String msgType = "normal";

    @Column(name = "importance")
    @Builder.Default
    private Double importance = 0.0;

    @Column(columnDefinition = "TEXT")
    private String audios;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "JSONB")
    private String progress;
}
