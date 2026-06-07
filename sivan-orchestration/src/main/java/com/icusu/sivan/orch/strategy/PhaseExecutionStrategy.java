package com.icusu.sivan.orch.strategy;

import com.icusu.sivan.common.enums.SquadMode;
import com.icusu.sivan.domain.orchestration.PhaseNode;
import com.icusu.sivan.orch.executor.AgentCheckpoint;
import com.icusu.sivan.orch.executor.PhaseCallbacks;
import com.icusu.sivan.orch.executor.PhaseResult;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

/**
 * 阶段内执行策略 — 每种 SquadMode 对应一个实现。
 *
 * <p>替代 PhaseExecutor 中的 switch-case 分派。
 */
public interface PhaseExecutionStrategy {

    SquadMode supportedMode();

    Mono<PhaseResult> execute(PhaseNode phase, String input, UUID executionId,
                              UUID accountId, int phaseIndex, PhaseCallbacks callbacks,
                              List<AgentCheckpoint> resumeCheckpoints);
}
