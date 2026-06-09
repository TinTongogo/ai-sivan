package com.icusu.sivan.common;

/**
 * 编排模式 — 驱动 InnerGoalNode 的子节点调度策略。
 * <p>
 * V2.0 统一编排模式。
 */
public enum Mode {
    /** 无编排（ContentNode 或单节点树） */
    NONE,

    /** 顺序执行子节点 */
    SEQUENTIAL,

    /** 并发执行子节点 */
    PARALLEL,

    /** LLM 决策下一阶段 */
    CONDITIONAL,

    /** 规划→执行两阶段 */
    HIERARCHICAL,

    /** 并行→合成 */
    CONSENSUS
}
