package com.icusu.sivan.orch.strategy;

import com.icusu.sivan.common.enums.SquadMode;
import com.icusu.sivan.orch.executor.*;
import com.icusu.sivan.domain.orchestration.ContextPackage;
import com.icusu.sivan.domain.orchestration.PhaseNode;
import com.icusu.sivan.domain.orchestration.PhaseOutput;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

/**
 * HIERARCHICAL 阶段间调度：Phase[0] 规划 → 后续 phases 按规划执行。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HierarchicalSchedulingStrategy implements PhaseSchedulingStrategy {

    private final PhaseExecutor phaseExecutor;

    @Override
    public SquadMode supportedMode() {
        return SquadMode.HIERARCHICAL;
    }

    @Override
    public SchedulePlan schedule(List<PhaseNode> phases) {
        List<PhaseNode> valid = phases != null ? phases : List.of();
        if (valid.isEmpty()) return new SchedulePlan(List.of());
        ScheduleGroup planning = new ScheduleGroup(GroupMode.SEQUENTIAL, List.of(valid.get(0)));
        if (valid.size() == 1) return new SchedulePlan(List.of(planning));
        ScheduleGroup execution = new ScheduleGroup(GroupMode.PARALLEL, valid.subList(1, valid.size()));
        return new SchedulePlan(List.of(planning, execution));
    }

    @Override
    public Mono<PhaseResult> execute(List<PhaseNode> phases, ContextPackage context,
                                     UUID executionId, UUID accountId,
                                     PhaseCallbacks callbacks, int startPhase) {
        String input = context.getInput();
        // Phase[0] 规划阶段
        if (startPhase <= 0 && !phases.isEmpty()
                && phases.get(0).getAgents() != null && !phases.get(0).getAgents().isEmpty()) {
            if (callbacks.isCancelled(executionId)) return Mono.just(PhaseResult.paused(input, "CANCELLED"));
            if (callbacks.isTimedOut(executionId)) return Mono.just(PhaseResult.paused(input, "TIMEOUT"));

            PhaseResult pre = callbacks.preDispatchPhase(phases.get(0), 0, input, executionId);
            if (pre != null) return Mono.just(pre);

            long phaseStartMs = System.currentTimeMillis();
            return phaseExecutor.dispatchPhase(phases.get(0), input, executionId,
                    accountId, 0, callbacks, null)
                    .flatMap(planResult -> {
                        if (planResult.paused()) {
                            callbacks.onPhasePaused(phases.get(0), 0, input, planResult);
                            return Mono.just(PhaseResult.paused(input, planResult.pauseReason()));
                        }
                        long durationMs = System.currentTimeMillis() - phaseStartMs;
                        callbacks.onPhaseCompleted(phases.get(0), 0, planResult.content(), durationMs);
                        PhaseOutput planOutput = new PhaseOutput(planResult.content());
                        callbacks.onArtifactGenerated(executionId, 0, planOutput);
                        ContextPackage afterPlan = context.withPhaseOutput(
                                0, planOutput);
                        return executeExecutionPhases(phases, planResult.content(),
                                afterPlan, executionId, accountId, callbacks, Math.max(startPhase, 1));
                    });
        }

        return executeExecutionPhases(phases, input, context, executionId, accountId,
                callbacks, Math.max(startPhase, 1));
    }

    /** 递归执行 Phase[1..n]。 */
    private Mono<PhaseResult> executeExecutionPhases(List<PhaseNode> phases, String planResult,
                                                       ContextPackage context,
                                                       UUID executionId, UUID accountId,
                                                       PhaseCallbacks callbacks, int startIdx) {
        return executeSequentiallyWithPlan(phases, planResult, context,
                executionId, accountId, callbacks, startIdx);
    }

    private Mono<PhaseResult> executeSequentiallyWithPlan(List<PhaseNode> phases, String planResult,
                                                           ContextPackage context, UUID executionId,
                                                           UUID accountId, PhaseCallbacks callbacks,
                                                           int idx) {
        // 找下一个就绪的执行阶段
        int readyIdx = findNextReadyExecutionPhase(phases, context, idx);
        if (readyIdx >= phases.size()) {
            return Mono.just(PhaseResult.success(context.getInput()));
        }

        PhaseNode phase = phases.get(readyIdx);

        if (callbacks.isCancelled(executionId)) return Mono.just(PhaseResult.paused(context.getInput(), "CANCELLED"));
        if (callbacks.isTimedOut(executionId)) return Mono.just(PhaseResult.paused(context.getInput(), "TIMEOUT"));

        String phaseInput = "【整体规划】\n" + planResult + "\n\n【本阶段任务】\n" + context.getInput();
        PhaseResult pre = callbacks.preDispatchPhase(phase, readyIdx, phaseInput, executionId);
        if (pre != null) return Mono.just(pre);

        long phaseStartMs = System.currentTimeMillis();
        return phaseExecutor.dispatchPhase(phase, phaseInput, executionId,
                accountId, readyIdx, callbacks, null)
                .flatMap(r -> {
                    if (r.paused()) {
                        callbacks.onPhasePaused(phase, readyIdx, context.getInput(), r);
                        return Mono.just(PhaseResult.paused(context.getInput(), r.pauseReason()));
                    }
                    long durationMs = System.currentTimeMillis() - phaseStartMs;
                    callbacks.onPhaseCompleted(phase, readyIdx, r.content(), durationMs);
                    PhaseOutput execOutput = new PhaseOutput(r.content());
                    callbacks.onArtifactGenerated(executionId, readyIdx, execOutput);
                    ContextPackage updated = context.withPhaseOutput(
                            readyIdx, execOutput);
                    // 完成后从头扫描
                    return executeSequentiallyWithPlan(phases, planResult, updated,
                            executionId, accountId, callbacks, 1);
                });
    }

    /** 在 Phase[1..n] 中找下一个就绪且未执行的阶段。 */
    private int findNextReadyExecutionPhase(List<PhaseNode> phases, ContextPackage context, int fromIdx) {
        for (int i = Math.max(fromIdx, 1); i < phases.size(); i++) {
            PhaseNode p = phases.get(i);
            if (p.getAgents() == null || p.getAgents().isEmpty()) continue;
            if (context.getPhaseOutputs().containsKey(p.getPhase())) continue;
            if (DagScheduleHelper.isReady(p, context)) return i;
        }
        return phases.size();
    }
}
