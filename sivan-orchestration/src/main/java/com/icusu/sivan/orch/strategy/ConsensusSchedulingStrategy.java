package com.icusu.sivan.orch.strategy;

import com.icusu.sivan.orch.executor.*;
import com.icusu.sivan.agent.prompt.OrchestrationPrompts;
import com.icusu.sivan.common.enums.SquadMode;
import com.icusu.sivan.domain.orchestration.ContextPackage;
import com.icusu.sivan.domain.orchestration.PhaseNode;
import com.icusu.sivan.domain.orchestration.PhaseOutput;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

/**
 * CONSENSUS 阶段间调度：所有阶段独立并行执行，LLM 综合。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConsensusSchedulingStrategy implements PhaseSchedulingStrategy {

    private final PhaseExecutor phaseExecutor;

    @Override
    public SquadMode supportedMode() {
        return SquadMode.CONSENSUS;
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

        return executeBatch(allPhases, context, executionId, accountId, callbacks);
    }

    /** 批量执行就绪阶段。全部完成后 LLM 综合。 */
    private Mono<PhaseResult> executeBatch(List<PhaseNode> allPhases, ContextPackage context,
                                            UUID executionId, UUID accountId,
                                            PhaseCallbacks callbacks) {
        String input = context.getInput();

        // 找出就绪且未执行的阶段
        List<PhaseNode> readyPhases = allPhases.stream()
                .filter(p -> DagScheduleHelper.isReady(p, context))
                .filter(p -> !context.getPhaseOutputs().containsKey(p.getPhase()))
                .toList();

        if (readyPhases.isEmpty()) {
            boolean allDone = allPhases.stream()
                    .allMatch(p -> context.getPhaseOutputs().containsKey(p.getPhase()));
            if (allDone) return doConsensus(context, callbacks, accountId, executionId);
            return Mono.just(PhaseResult.success(input)); // deadlock handled by DagValidator
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
                            log.info("CONSENSUS 阶段暂停: phase={}, reason={}",
                                    pwr.phase().getName(), pwr.result().pauseReason());
                            callbacks.onPhasePaused(pwr.phase(), pwr.phase().getPhase(), input, pwr.result());
                            return Mono.just(PhaseResult.paused(input, pwr.result().pauseReason()));
                        }
                        PhaseOutput po = new PhaseOutput(pwr.result().content());
                        callbacks.onArtifactGenerated(executionId, pwr.phase().getPhase(), po);
                        updated = updated.withPhaseOutput(
                                pwr.phase().getPhase(), po);
                    }

                    // 还有未完成的阶段 → 递归
                    final ContextPackage afterBatch = updated;
                    boolean hasRemaining = allPhases.stream()
                            .anyMatch(p -> !afterBatch.getPhaseOutputs().containsKey(p.getPhase()));
                    if (hasRemaining) {
                        return executeBatch(allPhases, afterBatch, executionId, accountId, callbacks);
                    }

                    // 全部完成 → LLM 综合
                    return doConsensus(afterBatch, callbacks, accountId, executionId);
                });
    }

    private Mono<PhaseResult> doConsensus(ContextPackage context, PhaseCallbacks callbacks,
                                            UUID accountId, UUID executionId) {
        var outputs = context.getPhaseOutputs();
        List<java.util.Map.Entry<String, String>> phaseResults = new java.util.ArrayList<>();
        for (var entry : outputs.entrySet()) {
            phaseResults.add(new java.util.AbstractMap.SimpleEntry<>(
                    "阶段" + entry.getKey(), entry.getValue().content()));
        }
        String synthesisPrompt = "你是灵枢（Sivan），综合分析各阶段输出得出最终结论。\n\n"
                + OrchestrationPrompts.consensusInterPhaseUser(phaseResults).content();

        var ctx = callbacks.createTokenContext(accountId, executionId);
        return callbacks.callLlm(synthesisPrompt, ctx)
                .flatMap(synthesis -> {
                    callbacks.afterAgentLlm("综合阶段", synthesis);
                    return Mono.just(PhaseResult.success("【综合结论】\n" + synthesis.content()));
                });
    }

    private record PhaseWithResult(PhaseNode phase, PhaseResult result) {}
}
