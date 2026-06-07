package com.icusu.sivan.orch.executor;

/**
 * 单个 Agent 的执行检查点，记录执行进度用于 HITL 恢复时的断点续跑。
 *
 * @param agentIndex Agent 在阶段列表中的序号
 * @param agentName  Agent 名称
 * @param status      COMPLETED / RUNNING
 * @param output      Agent 的输出内容（SEQUENTIAL 恢复时作为下一 Agent 的输入）
 * @param mode        模式标记：CONSENSUS 场景标记 AGREE / DISSENT；HIERARCHICAL 标记 subtask
 */
public record AgentCheckpoint(
        int agentIndex,
        String agentName,
        String status,
        String output,
        String mode
) {}
