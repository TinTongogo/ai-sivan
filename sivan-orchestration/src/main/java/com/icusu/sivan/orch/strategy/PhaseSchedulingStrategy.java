package com.icusu.sivan.orch.strategy;

import com.icusu.sivan.common.enums.SquadMode;
import com.icusu.sivan.domain.orchestration.ContextPackage;
import com.icusu.sivan.domain.orchestration.PhaseNode;
import com.icusu.sivan.orch.executor.PhaseCallbacks;
import com.icusu.sivan.orch.executor.PhaseResult;
import com.icusu.sivan.orch.executor.SchedulePlan;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

/**
 * 阶段间调度策略 — 每种 SquadMode 对应一个实现。
 *
 * <p>替代 PhaseScheduler 中的 switch-case 分派。
 */
public interface PhaseSchedulingStrategy {

    SquadMode supportedMode();

    SchedulePlan schedule(List<PhaseNode> phases);

    Mono<PhaseResult> execute(List<PhaseNode> phases, ContextPackage context,
                              UUID executionId, UUID accountId,
                              PhaseCallbacks callbacks, int startPhase);
}
