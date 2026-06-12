package com.icusu.sivan.agent.routing;

import java.util.Map;
import java.util.UUID;

/**
 * 森林节点适配器 — 桥接 {@link com.icusu.sivan.domain.forest.tree.TreeNode} 与路由策略。
 * <p>
 * 避免路由策略直接依赖领域实体，保持接口隔离。
 */
public record ForestNodeAdapter(
        String nodeId,
        String nodeType,
        String agentName,
        String serverId,
        UUID accountId,
        Map<String, String> metadata
) {
    public static ForestNodeAdapter from(
            String nodeId, String nodeType, String agentName,
            String serverId, UUID accountId, Map<String, String> metadata) {
        return new ForestNodeAdapter(nodeId, nodeType, agentName, serverId, accountId, metadata);
    }
}
