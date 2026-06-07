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
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

/**
 * PARALLEL 阶段间调度：所有阶段并发执行。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ParallelSchedulingStrategy implements PhaseSchedulingStrategy {

    private final PhaseExecutor phaseExecutor;
    private final ISquadExecutionRepository squadExecutionRepository;

    @Override
    public SquadMode supportedMode() {
        return SquadMode.PARALLEL;
    }

    @Override
    public SchedulePlan schedule(List<PhaseNode> phases) {
        List<PhaseNode> valid = phases != null ? phases : List.of();
        return new SchedulePlan(List.of(new ScheduleGroup(GroupMode.PARALLEL, valid)));
    }

    @Override
    public Mono<PhaseResult> execute(List<PhaseNode> phases, ContextPackage context,
                                     UUID executionId, UUID accountId,
                                     PhaseCallbacks callbacks, int startPhase) {
        String input = context.getInput();
        List<PhaseNode> allPhases = phases.stream()
                .filter(p -> p.getAgents() != null && !p.getAgents().isEmpty())
                .filter(p -> p.getPhase() >= startPhase)
                .toList();
        if (allPhases.isEmpty()) return Mono.just(PhaseResult.success(input));

        if (callbacks.isCancelled(executionId)) return Mono.just(PhaseResult.paused(input, "CANCELLED"));
        if (callbacks.isTimedOut(executionId)) return Mono.just(PhaseResult.paused(input, "TIMEOUT"));

        return executeBatch(allPhases, context, executionId, accountId, callbacks, startPhase);
    }

    /** 批量执行就绪阶段，完成后递归处理未完成的阶段。 */
    private Mono<PhaseResult> executeBatch(List<PhaseNode> allPhases, ContextPackage context,
                                            UUID executionId, UUID accountId,
                                            PhaseCallbacks callbacks, int startPhase) {
        String input = context.getInput();

        // 找出就绪且未执行的阶段
        List<PhaseNode> readyPhases = allPhases.stream()
                .filter(p -> DagScheduleHelper.isReady(p, context))
                .filter(p -> !context.getPhaseOutputs().containsKey(p.getPhase()))
                .toList();

        if (readyPhases.isEmpty()) {
            // 无就绪阶段：检查是否全部完成
            boolean allDone = allPhases.stream()
                    .allMatch(p -> context.getPhaseOutputs().containsKey(p.getPhase()));
            return Mono.just(allDone ? PhaseResult.success(input)
                    : PhaseResult.success(input)); // deadlock 由 DagValidator 负责
        }

        // HITL 预检查
        for (PhaseNode phase : readyPhases) {
            PhaseResult pre = callbacks.preDispatchPhase(phase, phase.getPhase(), input, executionId);
            if (pre != null) return Mono.just(pre);
        }

        return Flux.fromIterable(readyPhases)
                .flatMap(phase -> {
                    long phaseStartMs = System.currentTimeMillis();
                    return phaseExecutor.dispatchPhase(phase, input, executionId,
                            accountId, phase.getPhase(), callbacks, null)
                            .flatMap(r -> {
                                if (!r.paused()) {
                                    long durationMs = System.currentTimeMillis() - phaseStartMs;
                                    callbacks.onPhaseCompleted(phase, phase.getPhase(), r.content(), durationMs);
                                }
                                return Mono.just(new PhaseWithResult(phase, r));
                            });
                })
                .collectList()
                .flatMap(results -> {
                    // 检查是否有暂停
                    ContextPackage updated = context;
                    for (PhaseWithResult pwr : results) {
                        if (pwr.result().paused()) {
                            PhaseNode phase = pwr.phase();
                            log.info("并行分组阶段暂停: phase={}, reason={}",
                                    phase.getName(), pwr.result().pauseReason());
                            callbacks.onPhasePaused(phase, phase.getPhase(), input, pwr.result());
                            squadExecutionRepository.updateCurrentPhase(executionId, phase.getPhase());
                            return Mono.just(PhaseResult.paused(input, pwr.result().pauseReason()));
                        }
                        PhaseOutput po = new PhaseOutput(pwr.result().content());
                        callbacks.onArtifactGenerated(executionId, pwr.phase().getPhase(), po);
                        updated = updated.withPhaseOutput(
                                pwr.phase().getPhase(), po);
                    }

                    // 还有未完成的阶段 → 递归处理
                    final ContextPackage afterBatch = updated;
                    boolean hasRemaining = allPhases.stream()
                            .anyMatch(p -> !afterBatch.getPhaseOutputs().containsKey(p.getPhase()));
                    if (hasRemaining) {
                        return executeBatch(allPhases, afterBatch, executionId, accountId, callbacks, startPhase);
                    }

                    // 全部完成 → 合并输出
                    StringBuilder merged = new StringBuilder();
                    for (PhaseWithResult pwr : results) {
                        PhaseNode phase = pwr.phase();
                        merged.append("【阶段").append(phase.getPhase()).append(": ")
                                .append(phase.getName()).append("】\n")
                                .append(pwr.result().content()).append("\n\n");
                        squadExecutionRepository.updateCurrentPhase(executionId, phase.getPhase());
                    }
                    return Mono.just(PhaseResult.success(merged.toString().strip()));
                });
    }

    private record PhaseWithResult(PhaseNode phase, PhaseResult result) {}
}
