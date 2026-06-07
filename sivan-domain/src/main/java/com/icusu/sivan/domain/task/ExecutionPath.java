package com.icusu.sivan.domain.task;

/**
 * 执行路径。表示从请求到执行模式的分发结果，
 * 统一 CHAT / SINGLE_AGENT / SQUAD 三种形态。
 *
 * @param shape       执行形态
 * @param squadMode   SQUAD 模式下阶段间执行模式（可选）
 * @param phaseMode   SQUAD 模式下阶段内执行模式（可选）
 * @param topologyJson SQUAD 模式下的阶段拓扑 JSON（可选）
 * @param reason      选择说明
 */
public record ExecutionPath(
        ExecutionShape shape,
        String squadMode,
        String phaseMode,
        String topologyJson,
        String reason
) {

    /** CHAT 快捷工厂。 */
    public static ExecutionPath chat(String reason) {
        return new ExecutionPath(ExecutionShape.CHAT, null, null, null, reason);
    }

    /** SINGLE_AGENT 快捷工厂。 */
    public static ExecutionPath singleAgent(String reason) {
        return new ExecutionPath(ExecutionShape.SINGLE_AGENT, null, null, null, reason);
    }

    /** SQUAD 快捷工厂。 */
    public static ExecutionPath squad(String squadMode, String phaseMode, String topologyJson, String reason) {
        return new ExecutionPath(ExecutionShape.SQUAD, squadMode, phaseMode, topologyJson, reason);
    }

    public ExecutionPath {
        if (shape == null) throw new IllegalArgumentException("shape 不能为空");
    }
}
