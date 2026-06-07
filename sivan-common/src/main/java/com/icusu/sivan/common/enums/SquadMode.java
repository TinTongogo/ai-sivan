package com.icusu.sivan.common.enums;

/** Squad 编排模式（两维度复用）：SEQUENTIAL = 顺序执行，PARALLEL = 并行执行，CONDITIONAL = 条件路由执行，HIERARCHICAL = 层级分解执行，CONSENSUS = 多 Agent 共识执行。同时用于阶段间（Squad.mode）和阶段内（PhaseNode.mode）编排。 */
public enum SquadMode {
    SEQUENTIAL,
    PARALLEL,
    CONDITIONAL,
    HIERARCHICAL,
    CONSENSUS
}
