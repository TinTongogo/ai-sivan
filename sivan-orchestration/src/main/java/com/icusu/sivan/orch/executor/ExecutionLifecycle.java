package com.icusu.sivan.orch.executor;

import com.icusu.sivan.domain.orchestration.PhaseNode;
import com.icusu.sivan.domain.orchestration.PhaseOutput;

import java.util.UUID;

/**
 * 执行生命周期回调 — PhaseCallbacks 的执行监控职责拆分。
 */
public interface ExecutionLifecycle {

    default void publishEvent(UUID executionId, String status, Integer phase,
                              String phaseName, String message) {}

    default void afterAgentLlm(String agentLabel, LlmStrategy.LlmResult result) {}

    default boolean isCancelled(UUID executionId) { return false; }

    default boolean isTimedOut(UUID executionId) { return false; }

    default PhaseResult preDispatchPhase(PhaseNode phase, int phaseIndex,
                                         String input, UUID executionId) { return null; }

    default void onPhasePaused(PhaseNode phase, int phaseIndex,
                               String input, PhaseResult result) {}

    default void onPhaseCompleted(PhaseNode phase, int phaseIndex,
                                  String output, long durationMs) {}

    /** 阶段产出 artifact 时回调。artifactRefs 非空时才触发。 */
    default void onArtifactGenerated(UUID executionId, int phaseIndex, PhaseOutput output) {}
}
