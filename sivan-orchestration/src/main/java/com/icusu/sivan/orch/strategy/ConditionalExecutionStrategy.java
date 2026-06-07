package com.icusu.sivan.orch.strategy;

import com.icusu.sivan.common.enums.SquadMode;
import com.icusu.sivan.domain.orchestration.PhaseNode;
import com.icusu.sivan.domain.shared.util.CosineSimilarity;
import com.icusu.sivan.domain.shared.port.IEmbeddingService;
import com.icusu.sivan.orch.executor.AgentCheckpoint;
import com.icusu.sivan.orch.executor.PhaseCallbacks;
import com.icusu.sivan.orch.executor.PhaseResult;
import com.icusu.sivan.orch.executor.SquadPipelineAdapter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

/**
 * CONDITIONAL 阶段内执行：Embedding 语义匹配 outputFilter，匹配则执行，否则跳过。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConditionalExecutionStrategy implements PhaseExecutionStrategy {

    private static final double CONDITIONAL_THRESHOLD = 0.75;

    private final IEmbeddingService embeddingService;
    private final SquadPipelineAdapter squadPipelineAdapter;

    @Override
    public SquadMode supportedMode() {
        return SquadMode.CONDITIONAL;
    }

    @Override
    public Mono<PhaseResult> execute(PhaseNode phase, String input, UUID executionId,
                                      UUID accountId, int phaseIndex, PhaseCallbacks callbacks,
                                      List<AgentCheckpoint> resumeCheckpoints) {
        String filter = phase.getOutputFilter();
        if (filter != null && !filter.isBlank()) {
            boolean matches = matchesFilter(filter, input);
            if (!matches) {
                log.info("CONDITIONAL 阶段不匹配 filter={}，跳过", filter);
                callbacks.publishEvent(executionId, "RUNNING", phaseIndex, phase.getName(),
                        "条件不匹配，跳过阶段");
                return Mono.just(PhaseResult.success(input));
            }
            callbacks.publishEvent(executionId, "RUNNING", phaseIndex, phase.getName(),
                    "条件匹配，执行阶段");
        }
        return squadPipelineAdapter.executePhase(phase, input, executionId,
                accountId, phaseIndex, callbacks, resumeCheckpoints);
    }

    /** 语义匹配：优先用 Embedding cosine similarity，失败时回退到关键词匹配。 */
    private boolean matchesFilter(String filter, String input) {
        try {
            float[] inputVec = embeddingService.embed(input);
            float[] filterVec = embeddingService.embed(filter);
            double similarity = CosineSimilarity.compute(inputVec, filterVec);
            log.debug("CONDITIONAL 语义匹配: similarity={}, filter={}",
                    String.format("%.4f", similarity), filter);
            return similarity >= CONDITIONAL_THRESHOLD;
        } catch (Exception e) {
            log.warn("Embedding 服务不可用，回退到关键词匹配: {}", e.getMessage());
            return input.toLowerCase().contains(filter.toLowerCase());
        }
    }
}
