package com.icusu.sivan.orch;

import com.icusu.sivan.orch.executor.*;
import com.icusu.sivan.domain.orchestration.Squad;
import com.icusu.sivan.domain.orchestration.SquadExecution;

import java.util.UUID;

/**
 * 编排引擎统一接口，定义"将 Squad 编排为执行计划并调度执行"的契约。
 * <p>
 * 当前实现：
 * <ul>
 *   <li>{@link SquadOrchestrator} — 同步编排器（对话入口）</li>
 *   <li>{@link SquadExecutionEngine} — 异步编排引擎（管理页面入口）</li>
 * </ul>
 * 两套引擎通过 {@link PhaseCallbacks} 参数化行为差异，
 * 共享 {@link PhaseScheduler} 和 {@link PhaseExecutor}。
 */
public interface OrchestrationEngine {

    /** 引擎类型标识。 */
    String SYNC = "SYNC";
    String ASYNC = "ASYNC";

    /** 引擎标识。 */
    String engineType();

    /** 启动 Squad 执行的入口。 */
    void execute(Squad squad, SquadExecution execution, UUID accountId);

    /** 判断引擎是否支持给定的 Squad 执行模式。 */
    default boolean supports(Squad squad) {
        return squad != null && squad.getPhases() != null && !squad.getPhases().isEmpty();
    }

    /**
     * 定时恢复过期 HITL 审核。
     * 异步引擎覆盖此方法以 {@code @Scheduled} 调度；同步引擎保留空实现。
     */
    default void autoResumeExpiredHitl() {}
}
