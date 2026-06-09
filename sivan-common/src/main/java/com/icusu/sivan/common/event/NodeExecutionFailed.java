package com.icusu.sivan.common.event;

import java.time.Instant;

/** 节点执行失败事件。 */
public record NodeExecutionFailed(
        String nodeId,
        String forestId,
        String reason,
        String accountId,
        Instant occurredAt
) {}
