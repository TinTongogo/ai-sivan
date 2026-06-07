package com.icusu.sivan.domain.orchestration;

/**
 * HITL 审核决策值对象。扩展 HITL 操作从 approve/reject 二选一到五种操作。
 *
 * @param action          操作类型
 * @param correctedContent CORRECT 时注入的修正内容
 * @param restartHint      RESTART_PHASE / RESTART_AGENT 时的修正提示
 * @param restartAgent     RESTART_AGENT 时的 agent 名称
 */
public record HitlDecision(
        Action action,
        String correctedContent,
        String restartHint,
        String restartAgent
) {

    public enum Action {
        APPROVE,          // 通过，继续
        REJECT,           // 驳回，终止
        CORRECT,          // 修正后继续
        RESTART_PHASE,    // 重新执行本阶段
        RESTART_AGENT     // 从指定 Agent 重跑
    }

    public HitlDecision {
        if (action == null) {
            throw new IllegalArgumentException("action 不能为空");
        }
        if (action == Action.CORRECT && (correctedContent == null || correctedContent.isBlank())) {
            throw new IllegalArgumentException("CORRECT 操作必须提供 correctedContent");
        }
        if (action == Action.RESTART_PHASE && (restartHint == null || restartHint.isBlank())) {
            throw new IllegalArgumentException("RESTART_PHASE 操作必须提供 restartHint");
        }
        if (action == Action.RESTART_AGENT) {
            if (restartAgent == null || restartAgent.isBlank()) {
                throw new IllegalArgumentException("RESTART_AGENT 操作必须提供 restartAgent");
            }
            if (restartHint == null || restartHint.isBlank()) {
                throw new IllegalArgumentException("RESTART_AGENT 操作必须提供 restartHint");
            }
        }
    }

    public static HitlDecision approve() {
        return new HitlDecision(Action.APPROVE, null, null, null);
    }

    public static HitlDecision reject() {
        return new HitlDecision(Action.REJECT, null, null, null);
    }

    public static HitlDecision correct(String correctedContent) {
        return new HitlDecision(Action.CORRECT, correctedContent, null, null);
    }

    public static HitlDecision restartPhase(String restartHint) {
        return new HitlDecision(Action.RESTART_PHASE, null, restartHint, null);
    }

    public static HitlDecision restartAgent(String agentName, String restartHint) {
        return new HitlDecision(Action.RESTART_AGENT, null, restartHint, agentName);
    }
}
