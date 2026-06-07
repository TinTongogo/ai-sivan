package com.icusu.sivan.orch.executor;

import java.util.List;

/**
 * 阶段执行结果，含执行内容、暂停标记和原因。
 * 使 HITL 暂停/恢复可以通过标准返回类型传递，不再依赖字符串标记。
 * Agent 检查点列表记录每个 Agent 的执行进度，用于 HITL 恢复时的断点续跑。
 */
public record PhaseResult(String content, boolean paused, String pauseReason, List<AgentCheckpoint> checkpoints) {

    public static PhaseResult success(String content) {
        return new PhaseResult(content, false, null, List.of());
    }

    public static PhaseResult success(String content, List<AgentCheckpoint> checkpoints) {
        return new PhaseResult(content, false, null, checkpoints);
    }

    public static PhaseResult paused(String reason) {
        return new PhaseResult(null, true, reason, List.of());
    }

    /** 带上游输出内容的暂停（用于阶段间传递已执行阶段的累积输出）。 */
    public static PhaseResult paused(String content, String reason) {
        return new PhaseResult(content, true, reason, List.of());
    }

    /** 带检查点的暂停（HITL 恢复时保留已完成 Agent 的输出）。 */
    public static PhaseResult paused(String content, String reason, List<AgentCheckpoint> checkpoints) {
        return new PhaseResult(content, true, reason, checkpoints);
    }

    /** 获取内容（paused 时也可能有上游输出的内容）。 */
    @Override
    public String content() {
        return content != null ? content : "";
    }
}
