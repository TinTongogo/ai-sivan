package com.icusu.sivan.domain.forest.template;

import com.icusu.sivan.domain.forest.tree.ExecutableNode;

import java.time.Instant;
import java.util.UUID;

/**
 * GoalTree 模板 — 可复用的树结构。
 * 通过 {@link #deepClone()} 创建可执行实例，不修改模板本身。
 */
public class GoalTreeTemplate {

    private final UUID templateId;
    private final UUID accountId;
    private final String name;
    private final String description;
    private final Instant createdAt;
    private Instant updatedAt;
    private int usageCount;
    private int successCount;
    private final ExecutableNode root;

    public GoalTreeTemplate(UUID accountId, String name, String description, ExecutableNode root) {
        this.templateId = UUID.randomUUID();
        this.accountId = accountId;
        this.name = name;
        this.description = description;
        this.root = root;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
        this.usageCount = 0;
        this.successCount = 0;
    }

    public UUID templateId() { return templateId; }
    public UUID accountId() { return accountId; }
    public String name() { return name; }
    public String description() { return description; }
    public ExecutableNode root() { return root; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
    public int usageCount() { return usageCount; }
    public int successCount() { return successCount; }
    public double successRate() { return usageCount > 0 ? (double) successCount / usageCount : 0; }

    /** 深拷贝模板为可执行实例，返回新的根节点（所有 status 重置为 PENDING）。 */
    public ExecutableNode deepClone() {
        usageCount++;
        updatedAt = Instant.now();
        return cloneNode(root);
    }

    public void recordExecution(boolean success) {
        if (success) successCount++;
        updatedAt = Instant.now();
    }

    @SuppressWarnings("unchecked")
    private <T extends ExecutableNode> T cloneNode(T node) {
        GoalTreeCloner cloner = new GoalTreeCloner();
        node.accept(cloner);
        return (T) cloner.getResult();
    }
}
