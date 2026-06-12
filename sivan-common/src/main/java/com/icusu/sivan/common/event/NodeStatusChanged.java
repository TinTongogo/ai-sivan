package com.icusu.sivan.common.event;

import com.icusu.sivan.common.NodeStatus;

import java.time.Instant;

/** 节点状态变更事件。 */
public record NodeStatusChanged(
        String nodeId,
        NodeStatus oldStatus,
        NodeStatus newStatus,
        String forestId,
        String accountId,
        Instant occurredAt,
        Integer durationMs,
        Integer totalTokens
) {
    public NodeStatusChanged(String nodeId, NodeStatus oldStatus, NodeStatus newStatus,
                             String forestId, String accountId, Instant occurredAt) {
        this(nodeId, oldStatus, newStatus, forestId, accountId, occurredAt, null, null);
    }
}
