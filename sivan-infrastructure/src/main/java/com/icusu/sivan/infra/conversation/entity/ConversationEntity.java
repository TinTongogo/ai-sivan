package com.icusu.sivan.infra.conversation.entity;

import com.icusu.sivan.infra.shared.entity.BaseEntity;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * conversations 表 JPA 实体，表示用户与智能体的对话会话。
 */
@Entity
@Table(name = "conversations", indexes = {
        @Index(name = "idx_conversation_account", columnList = "account_id")
})
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConversationEntity extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID conversationId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "project_id")
    private UUID projectId;

    @Column(length = 256)
    @Builder.Default
    private String title = "新对话";

    @Column(name = "knowledge_base_ids", columnDefinition = "TEXT DEFAULT '[]'")
    @Builder.Default
    private String knowledgeBaseIds = "[]";

    @Column(name = "mcp_server_ids", columnDefinition = "TEXT DEFAULT '[]'")
    @Builder.Default
    private String mcpServerIds = "[]";

    @Column(name = "goal_id")
    private UUID goalId;

    @Column(name = "message_count")
    @Builder.Default
    private Integer messageCount = 0;

    @Column(name = "compressed_context", columnDefinition = "TEXT")
    private String compressedContext;

    @Column(name = "compressed_up_to_msg_id")
    private UUID compressedUpToMsgId;

    @Column(name = "last_message_at")
    private OffsetDateTime lastMessageAt;
}
