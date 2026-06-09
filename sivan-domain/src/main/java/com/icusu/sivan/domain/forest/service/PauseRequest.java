package com.icusu.sivan.domain.forest.service;

import java.util.List;

/**
 * HITL 暂停请求 — {@link CheckpointHandler#check(ExecutableNode, ExecutionContext)} 的返回值。
 *
 * @param nodeId  暂停的节点 ID
 * @param reason  暂停原因（如 "等待审批: xxx"）
 * @param actions 可执行的操作列表（如 "approve", "reject"）
 */
public record PauseRequest(
        String nodeId,
        String reason,
        List<String> actions
) {

    /** 暂停请求被批准。 */
    public boolean isApproved() {
        return actions != null && actions.contains("approve");
    }

    /** 暂停请求被拒绝。 */
    public boolean isRejected() {
        return actions != null && actions.contains("reject");
    }
}
