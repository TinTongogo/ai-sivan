package com.icusu.sivan.domain.forest;

import com.icusu.sivan.common.NodeStatus;

import java.time.Instant;
import java.util.Map;

/**
 * 森林事件族 — 跨上下文的不可变值对象。
 * <p>
 * 每个事件记录携带 {@code nodeId} + {@code forestId} + {@code accountId}，
 * 确保订阅者能定位所属用户和所属树。
 */
public final class ForestEvent {

    private final String nodeId;
    private final String forestId;
    private final String accountId;
    private final EventType type;
    private final String message;
    private final Instant occurredAt;
    private final String statusBefore;
    private final String statusAfter;
    private final Map<String, Object> metadata;

    public ForestEvent(String nodeId, String forestId, String accountId,
                       EventType type, String message) {
        this(nodeId, forestId, accountId, type, message, null, null, null);
    }

    public ForestEvent(String nodeId, String forestId, String accountId,
                       EventType type, String message,
                       NodeStatus statusBefore, NodeStatus statusAfter,
                       Map<String, Object> metadata) {
        this.nodeId = nodeId;
        this.forestId = forestId;
        this.accountId = accountId;
        this.type = type;
        this.message = message;
        this.statusBefore = statusBefore != null ? statusBefore.name() : null;
        this.statusAfter = statusAfter != null ? statusAfter.name() : null;
        this.metadata = metadata;
        this.occurredAt = Instant.now();
    }

    public String nodeId() { return nodeId; }
    public String getNodeId() { return nodeId; }
    public String forestId() { return forestId; }
    public String getForestId() { return forestId; }
    public String accountId() { return accountId; }
    public String getAccountId() { return accountId; }
    public EventType type() { return type; }
    public EventType getType() { return type; }
    public String message() { return message; }
    public String getMessage() { return message; }
    public String statusBefore() { return statusBefore; }
    public String getStatusBefore() { return statusBefore; }
    public String statusAfter() { return statusAfter; }
    public String getStatusAfter() { return statusAfter; }
    public Map<String, Object> metadata() { return metadata; }
    public Map<String, Object> getMetadata() { return metadata; }
    public Instant occurredAt() { return occurredAt; }
    public Instant getOccurredAt() { return occurredAt; }

    // ===== 工厂方法 =====

    /** 创建生命周期事件（不含状态变更）。 */
    public static ForestEvent lifecycle(String nodeId, String forestId, String accountId, EventType eventType) {
        return new ForestEvent(nodeId, forestId, accountId, eventType, eventType.name());
    }

    /** 创建生命周期事件（携带节点状态变更）。 */
    public static ForestEvent lifecycleWithStatus(
            String nodeId, String forestId, String accountId,
            EventType eventType, NodeStatus statusBefore, NodeStatus statusAfter) {
        return new ForestEvent(nodeId, forestId, accountId, eventType, eventType.name(),
                statusBefore, statusAfter, null);
    }

    /** 创建生命周期事件（携带节点状态变更 + 元数据）。 */
    public static ForestEvent lifecycleWithStatusAndMeta(
            String nodeId, String forestId, String accountId,
            EventType eventType, NodeStatus statusBefore, NodeStatus statusAfter,
            Map<String, Object> metadata) {
        return new ForestEvent(nodeId, forestId, accountId, eventType, eventType.name(),
                statusBefore, statusAfter, metadata);
    }

    public static ForestEvent error(String nodeId, String forestId, String accountId, String message) {
        return new ForestEvent(nodeId, forestId, accountId, EventType.ERROR, message);
    }

    /** 创建错误事件（携带节点状态变更）。 */
    public static ForestEvent errorWithStatus(
            String nodeId, String forestId, String accountId, String message,
            NodeStatus statusBefore, NodeStatus statusAfter) {
        return new ForestEvent(nodeId, forestId, accountId, EventType.ERROR, message,
                statusBefore, statusAfter, null);
    }

    public static ForestEvent detail(String nodeId, String forestId, String accountId, String message) {
        return new ForestEvent(nodeId, forestId, accountId, EventType.DETAIL, message);
    }

    public static ForestEvent thinking(String nodeId, String forestId, String accountId, String text) {
        return new ForestEvent(nodeId, forestId, accountId, EventType.THINKING, text);
    }

    public static ForestEvent pause(String nodeId, String forestId, String accountId, String reason) {
        return new ForestEvent(nodeId, forestId, accountId, EventType.PAUSE, reason);
    }

    /** 工具调用：LLM 请求调用某个工具。message 为 JSON 格式 {name, args, id}。 */
    public static ForestEvent toolCall(String nodeId, String forestId, String accountId, String message) {
        return new ForestEvent(nodeId, forestId, accountId, EventType.TOOL_CALL, message);
    }

    /** 工具结果：工具执行完毕。message 为 JSON 格式 {name, success, output}。 */
    public static ForestEvent toolResult(String nodeId, String forestId, String accountId, String message) {
        return new ForestEvent(nodeId, forestId, accountId, EventType.TOOL_RESULT, message);
    }

    /** CONDITIONAL 分支决策：LLM 决定是否继续下一阶段。message 为 JSON 格式 {chosen, skipped, reason}。 */
    public static ForestEvent branchDecision(String nodeId, String forestId, String accountId, String message) {
        return new ForestEvent(nodeId, forestId, accountId, EventType.BRANCH_DECISION, message);
    }

    /** HITL 用户批准恢复。 */
    public static ForestEvent hitlResume(String nodeId, String forestId, String accountId, String message) {
        return new ForestEvent(nodeId, forestId, accountId, EventType.HITL_RESUME, message);
    }

    /** HITL 用户拒绝。 */
    public static ForestEvent hitlReject(String nodeId, String forestId, String accountId, String message) {
        return new ForestEvent(nodeId, forestId, accountId, EventType.HITL_REJECT, message);
    }

    // ===== 事件类型枚举 =====

    public enum EventType {
        /** 节点生命周期：开始、结束 */
        LIFECYCLE,
        /** 详细日志：token 消耗、中间结果 */
        DETAIL,
        /** 模型推理过程（thinking） */
        THINKING,
        /** 错误 */
        ERROR,
        /** HITL 暂停 */
        PAUSE,
        /** HITL 用户批准恢复 */
        HITL_RESUME,
        /** HITL 用户拒绝 */
        HITL_REJECT,
        /** 里程碑完成 */
        MILESTONE,
        /** 工具调用 */
        TOOL_CALL,
        /** 工具结果 */
        TOOL_RESULT,
        /** CONDITIONAL 分支决策 */
        BRANCH_DECISION
    }
}
