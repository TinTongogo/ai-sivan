package com.icusu.sivan.orch.strategy;

import com.icusu.sivan.common.enums.SquadMode;
import com.icusu.sivan.orch.executor.*;
import com.icusu.sivan.domain.orchestration.ContextPackage;
import com.icusu.sivan.domain.orchestration.PhaseNode;
import com.icusu.sivan.domain.orchestration.PhaseOutput;
import com.icusu.sivan.domain.orchestration.ISquadExecutionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

/**
 * SEQUENTIAL 阶段间调度：所有阶段按顺序依次执行。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SequentialSchedulingStrategy implements PhaseSchedulingStrategy {

    private final PhaseExecutor phaseExecutor;
    private final ISquadExecutionRepository squadExecutionRepository;

    @Override
    public SquadMode supportedMode() {
        return SquadMode.SEQUENTIAL;
    }

    @Override
    public SchedulePlan schedule(List<PhaseNode> phases) {
        List<PhaseNode> valid = phases != null ? phases : List.of();
        return new SchedulePlan(List.of(new ScheduleGroup(GroupMode.SEQUENTIAL, valid)));
    }

    @Override
    public Mono<PhaseResult> execute(List<PhaseNode> phases, ContextPackage context,
                                     UUID executionId, UUID accountId,
                                     PhaseCallbacks callbacks, int startPhase) {
        return executeSequentially(phases, context, executionId, accountId, callbacks, startPhase, 0);
    }

    /** 递归执行阶段链。dependsOn 未就绪的阶段跳过，每次完成后从头重扫。 */
    private Mono<PhaseResult> executeSequentially(List<PhaseNode> phases, ContextPackage context,
                                                    UUID executionId, UUID accountId,
                                                    PhaseCallbacks callbacks, int startPhase, int idx) {
        String currentContent = context.getInput();

        // 找到下一个就绪的阶段
        for (int i = idx; i < phases.size(); i++) {
            PhaseNode phase = phases.get(i);
            if (phase.getAgents() == null || phase.getAgents().isEmpty() || phase.getPhase() < startPhase) {
                continue;
            }
            if (!DagScheduleHelper.isReady(phase, context)) {
                continue; // dependsOn 未就绪，跳过
            }
            if (context.getPhaseOutputs().containsKey(phase.getPhase())) {
                continue; // 已完成
            }

            if (callbacks.isCancelled(executionId)) return Mono.just(PhaseResult.paused(currentContent, "CANCELLED"));
            if (callbacks.isTimedOut(executionId)) return Mono.just(PhaseResult.paused(currentContent, "TIMEOUT"));

            PhaseResult pre = callbacks.preDispatchPhase(phase, phase.getPhase(), currentContent, executionId);
            if (pre != null) return Mono.just(pre);

            squadExecutionRepository.updateCurrentPhase(executionId, phase.getPhase());

            long phaseStartMs = System.currentTimeMillis();
            int phaseIdx = i;
            return phaseExecutor.dispatchPhase(phase, currentContent, executionId, accountId, phase.getPhase(), callbacks, null)
                    .flatMap(r -> {
                        if (r.paused()) {
                            log.info("阶段暂停: phase={}, reason={}", phase.getName(), r.pauseReason());
                            callbacks.onPhasePaused(phase, phase.getPhase(), currentContent, r);
                            return Mono.just(PhaseResult.paused(currentContent, r.pauseReason()));
                        }
                        long durationMs = System.currentTimeMillis() - phaseStartMs;
                        callbacks.onPhaseCompleted(phase, phase.getPhase(), r.content(), durationMs);
                        PhaseOutput phaseOutput = new PhaseOutput(r.content());
                        callbacks.onArtifactGenerated(executionId, phase.getPhase(), phaseOutput);
                        ContextPackage updated = context.withPhaseOutput(
                                phase.getPhase(), phaseOutput);
                        // 完成后从头扫描（可能有之前跳过的阶段现在就绪了）
                        return executeSequentially(phases, updated, executionId, accountId,
                                callbacks, startPhase, 0);
                    });
        }

        // 无就绪阶段：检查是否全部完成
        boolean allDone = phases.stream()
                .filter(p -> p.getAgents() != null && !p.getAgents().isEmpty())
                .filter(p -> p.getPhase() >= startPhase)
                .allMatch(p -> context.getPhaseOutputs().containsKey(p.getPhase()));
        if (allDone) {
            return Mono.just(PhaseResult.success(currentContent));
        }
        // 有未完成但无就绪 → dependsOn 环形依赖或死锁
        log.warn("SEQUENTIAL 调度死锁：存在未完成阶段但无就绪节点，检查 dependsOn 设置");
        return Mono.just(PhaseResult.success(currentContent));
    }
}
