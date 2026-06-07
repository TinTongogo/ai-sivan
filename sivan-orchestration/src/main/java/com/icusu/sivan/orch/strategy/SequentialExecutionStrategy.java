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
 * SEQUENTIAL 阶段内执行：按顺序依次执行每个 Agent。
 */
@Component
@RequiredArgsConstructor
public class SequentialExecutionStrategy implements PhaseExecutionStrategy {

    private final SquadPipelineAdapter squadPipelineAdapter;

    @Override
    public SquadMode supportedMode() {
        return SquadMode.SEQUENTIAL;
    }

    @Override
    public Mono<PhaseResult> execute(PhaseNode phase, String input, UUID executionId,
                                      UUID accountId, int phaseIndex, PhaseCallbacks callbacks,
                                      List<AgentCheckpoint> resumeCheckpoints) {
        return squadPipelineAdapter.executePhase(phase, input, executionId,
                accountId, phaseIndex, callbacks, resumeCheckpoints);
    }
}
