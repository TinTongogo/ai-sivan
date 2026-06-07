package com.icusu.sivan.orch.strategy;

import com.icusu.sivan.common.enums.SquadMode;
import com.icusu.sivan.domain.orchestration.PhaseNode;
import com.icusu.sivan.orch.executor.AgentCheckpoint;
import com.icusu.sivan.orch.executor.PhaseCallbacks;
import com.icusu.sivan.orch.executor.PhaseResult;
import com.icusu.sivan.orch.executor.SquadPipelineAdapter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

/**
 * PARALLEL 阶段内执行：多个 Agent 并行执行。
 */
@Component
@RequiredArgsConstructor
public class ParallelExecutionStrategy implements PhaseExecutionStrategy {

    private final SquadPipelineAdapter squadPipelineAdapter;

    @Override
    public SquadMode supportedMode() {
        return SquadMode.PARALLEL;
    }

    @Override
    public Mono<PhaseResult> execute(PhaseNode phase, String input, UUID executionId,
                                      UUID accountId, int phaseIndex, PhaseCallbacks callbacks,
                                      List<AgentCheckpoint> resumeCheckpoints) {
        return squadPipelineAdapter.executePhase(phase, input, executionId,
                accountId, phaseIndex, callbacks, resumeCheckpoints);
    }
}
