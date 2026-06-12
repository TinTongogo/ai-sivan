package com.icusu.sivan.domain.compression;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 结构化记忆 — 从对话中提取的关键信息。
 * <p>
 * 设计文档 4.4 节。三种类型：DECISION（决策）、FACT（事实）、TECH（技术栈）。
 * 独立于原始消息存储，下次压缩时可直接引用。
 */
public class StructuredMemory {

    public enum Type { DECISION, FACT, TECH }

    private UUID memoryId;
    private UUID accountId;
    private Type type;
    private String content;
    private String sourceConversationId;
    private double importance;
    private int accessCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public StructuredMemory() {}

    public StructuredMemory(UUID accountId, Type type, String content, String sourceConversationId) {
        this.memoryId = UUID.randomUUID();
        this.accountId = accountId;
        this.type = type;
        this.content = content;
        this.sourceConversationId = sourceConversationId;
        this.importance = 0.5;
        this.accessCount = 0;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public static StructuredMemory decision(UUID accountId, String content, String convId) {
        return new StructuredMemory(accountId, Type.DECISION, content, convId);
    }

    public static StructuredMemory fact(UUID accountId, String content, String convId) {
        return new StructuredMemory(accountId, Type.FACT, content, convId);
    }

    public static StructuredMemory tech(UUID accountId, String content, String convId) {
        return new StructuredMemory(accountId, Type.TECH, content, convId);
    }

    // Getters / Setters
    public UUID getMemoryId() { return memoryId; }
    public void setMemoryId(UUID memoryId) { this.memoryId = memoryId; }
    public UUID getAccountId() { return accountId; }
    public void setAccountId(UUID accountId) { this.accountId = accountId; }
    public Type getType() { return type; }
    public void setType(Type type) { this.type = type; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getSourceConversationId() { return sourceConversationId; }
    public void setSourceConversationId(String sourceConversationId) { this.sourceConversationId = sourceConversationId; }
    public double getImportance() { return importance; }
    public void setImportance(double importance) { this.importance = importance; }
    public int getAccessCount() { return accessCount; }
    public void setAccessCount(int accessCount) { this.accessCount = accessCount; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
