package com.icusu.sivan.common.event;

import java.time.Instant;

/** 整棵 GoalTree 执行完成事件。 */
public record GoalTreeCompleted(
        String rootNodeId,
        String forestId,
        String accountId,
        String conversationId,
        boolean success,
        Instant completedAt,
        String templateId
) {
    public GoalTreeCompleted(String rootNodeId, String forestId, String accountId,
                             String conversationId, boolean success, Instant completedAt) {
        this(rootNodeId, forestId, accountId, conversationId, success, completedAt, null);
    }
}
