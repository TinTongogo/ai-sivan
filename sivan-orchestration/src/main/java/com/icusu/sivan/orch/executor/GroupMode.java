package com.icusu.sivan.orch.executor;

/**
 * 调度分组内阶段的执行模式。比 SquadMode 精简，仅控制组内阶段的编排方式。
 */
public enum GroupMode {
    /** 组内阶段依次执行 */
    SEQUENTIAL,
    /** 组内阶段并发执行 */
    PARALLEL
}
