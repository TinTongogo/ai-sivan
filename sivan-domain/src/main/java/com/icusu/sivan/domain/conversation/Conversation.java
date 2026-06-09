package com.icusu.sivan.domain.conversation;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 对话实体。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Conversation {

    private UUID conversationId;
    private UUID accountId;
    private UUID projectId;
    private String title;
    private Integer messageCount;
    private List<String> knowledgeBaseIds;
    private List<String> mcpServerIds;
    /** 延迟压缩快照：压缩后的上下文文本 */
    private String compressedContext;
    /** 压缩快照截止的消息 ID（此 ID 之后的增量消息需追加） */
    private UUID compressedUpToMsgId;
    private LocalDateTime lastMessageAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public void incrementMessageCount() {
        this.messageCount = (this.messageCount != null ? this.messageCount : 0) + 1;
        this.lastMessageAt = LocalDateTime.now();
    }

    public void bindKnowledgeBase(String kbId) {
        if (this.knowledgeBaseIds == null) this.knowledgeBaseIds = new java.util.ArrayList<>();
        if (!this.knowledgeBaseIds.contains(kbId)) this.knowledgeBaseIds.add(kbId);
    }

    public void bindMcpServer(String serverId) {
        if (this.mcpServerIds == null) this.mcpServerIds = new java.util.ArrayList<>();
        if (!this.mcpServerIds.contains(serverId)) this.mcpServerIds.add(serverId);
    }

    public void updateFrom(String title, UUID projectId, List<String> knowledgeBaseIds, List<String> mcpServerIds) {
        if (title != null) this.title = title;
        if (projectId != null) this.projectId = projectId;
        if (knowledgeBaseIds != null) this.knowledgeBaseIds = knowledgeBaseIds;
        if (mcpServerIds != null) this.mcpServerIds = mcpServerIds;
        this.updatedAt = LocalDateTime.now();
    }
}
