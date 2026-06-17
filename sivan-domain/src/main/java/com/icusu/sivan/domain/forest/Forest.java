package com.icusu.sivan.domain.forest;

import java.time.Instant;
import java.util.UUID;

/**
 * 森林聚合根 — 一棵目标树的完整上下文。
 * <p>
 * DDD 聚合根：加载 Forest 意味着加载整棵目标树。
 * 不加载其他 Forest 的节点，不跨聚合事务。
 */
public class Forest {

    private final UUID forestId;
    private final UUID accountId;
    private final UUID projectId;
    private final String title;
    private final String rootNodeId;
    private final Instant createdAt;
    private Instant updatedAt;

    public Forest(UUID forestId, UUID accountId, UUID projectId,
                  String title, String rootNodeId) {
        this.forestId = forestId;
        this.accountId = accountId;
        this.projectId = projectId;
        this.title = title;
        this.rootNodeId = rootNodeId;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public UUID forestId() { return forestId; }
    public UUID accountId() { return accountId; }
    public UUID projectId() { return projectId; }
    public String title() { return title; }
    public String rootNodeId() { return rootNodeId; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }

    public void touch() { this.updatedAt = Instant.now(); }
}
