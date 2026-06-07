package com.icusu.sivan.orch.strategy;

import com.icusu.sivan.common.enums.SquadMode;
import com.icusu.sivan.domain.orchestration.ContextPackage;
import com.icusu.sivan.domain.orchestration.PhaseNode;
import com.icusu.sivan.domain.orchestration.PhaseOutput;
import com.icusu.sivan.domain.shared.vo.TokenContext;
import com.icusu.sivan.orch.executor.GroupMode;
import com.icusu.sivan.orch.executor.LlmStrategy;
import com.icusu.sivan.orch.executor.PhaseCallbacks;
import com.icusu.sivan.orch.executor.PhaseExecutor;
import com.icusu.sivan.orch.executor.PhaseResult;
import com.icusu.sivan.orch.executor.SchedulePlan;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ConsensusSchedulingStrategyTest {

    @Mock private PhaseExecutor phaseExecutor;
    @Mock private PhaseCallbacks callbacks;

    private ConsensusSchedulingStrategy strategy;
    private final UUID executionId = UUID.randomUUID();
    private final UUID accountId = UUID.randomUUID();

    private final PhaseNode phase0 = PhaseNode.builder()
            .phase(0).name("分析").agents(List.of("a1")).build();
    private final PhaseNode phase1 = PhaseNode.builder()
            .phase(1).name("执行").agents(List.of("a2")).build();

    @BeforeEach
    void setUp() {
        strategy = new ConsensusSchedulingStrategy(phaseExecutor);
    }

    @Test
    void supportedMode_shouldReturnConsensus() {
        assertEquals(SquadMode.CONSENSUS, strategy.supportedMode());
    }

    @Test
    void schedule_shouldReturnSingleParallelGroup() {
        SchedulePlan plan = strategy.schedule(List.of(phase0, phase1));

        assertEquals(1, plan.getGroups().size());
        assertEquals(GroupMode.PARALLEL, plan.getGroups().get(0).getMode());
        assertEquals(2, plan.getGroups().get(0).getPhases().size());
    }

    @Test
    void execute_shouldRunAllPhases() {
        ContextPackage ctx = new ContextPackage("input", "task", Map.of(), null, null);

        when(callbacks.isCancelled(any())).thenReturn(false);
        when(callbacks.isTimedOut(any())).thenReturn(false);
        when(phaseExecutor.dispatchPhase(eq(phase0), anyString(), any(), any(), anyInt(), any(), any()))
                .thenReturn(Mono.just(PhaseResult.success("out0")));
        when(phaseExecutor.dispatchPhase(eq(phase1), anyString(), any(), any(), anyInt(), any(), any()))
                .thenReturn(Mono.just(PhaseResult.success("out1")));
        when(callbacks.createTokenContext(any(), any())).thenReturn(TokenContext.builder().build());
        when(callbacks.callLlm(anyString(), any()))
                .thenReturn(Mono.just(new LlmStrategy.LlmResult("synthesis", null)));

        PhaseResult result = strategy.execute(
                List.of(phase0, phase1), ctx, executionId, accountId, callbacks, 0).block();

        assertNotNull(result);
        assertFalse(result.paused());
        assertTrue(result.content().contains("【综合结论】"));
        assertTrue(result.content().contains("synthesis"));

        verify(phaseExecutor, times(2))
                .dispatchPhase(any(), anyString(), any(), any(), anyInt(), any(), any());
        verify(callbacks, times(2))
                .onPhaseCompleted(any(), anyInt(), anyString(), anyLong());
        verify(callbacks, times(2))
                .onArtifactGenerated(eq(executionId), anyInt(), any(PhaseOutput.class));
        verify(callbacks).callLlm(anyString(), any());
        verify(callbacks).afterAgentLlm(eq("综合阶段"), any());
    }

    @Test
    void execute_shouldPause_whenCancelled() {
        when(callbacks.isCancelled(any())).thenReturn(true);
        ContextPackage ctx = new ContextPackage("input", "task", Map.of(), null, null);

        PhaseResult result = strategy.execute(
                List.of(phase0), ctx, executionId, accountId, callbacks, 0).block();

        assertTrue(result.paused());
        assertEquals("CANCELLED", result.pauseReason());
        verifyNoInteractions(phaseExecutor);
    }

    @Test
    void execute_shouldPause_whenTimedOut() {
        when(callbacks.isCancelled(any())).thenReturn(false);
        when(callbacks.isTimedOut(any())).thenReturn(true);
        ContextPackage ctx = new ContextPackage("input", "task", Map.of(), null, null);

        PhaseResult result = strategy.execute(
                List.of(phase0), ctx, executionId, accountId, callbacks, 0).block();

        assertTrue(result.paused());
        assertEquals("TIMEOUT", result.pauseReason());
        verifyNoInteractions(phaseExecutor);
    }

    @Test
    void execute_shouldHandleEmptyPhases() {
        ContextPackage ctx = new ContextPackage("input", "task", Map.of(), null, null);

        PhaseResult result = strategy.execute(
                List.of(), ctx, executionId, accountId, callbacks, 0).block();

        assertNotNull(result);
        assertFalse(result.paused());
        assertEquals("input", result.content());
    }

    @Test
    void execute_shouldHandleNullAgents() {
        PhaseNode noAgentPhase = PhaseNode.builder()
                .phase(0).name("空阶段").agents(null).build();
        ContextPackage ctx = new ContextPackage("input", "task", Map.of(), null, null);

        PhaseResult result = strategy.execute(
                List.of(noAgentPhase), ctx, executionId, accountId, callbacks, 0).block();

        assertNotNull(result);
        assertFalse(result.paused());
        assertEquals("input", result.content());
    }

    @Test
    void execute_shouldFilterByStartPhase() {
        ContextPackage ctx = new ContextPackage("input", "task", Map.of(), null, null);

        when(callbacks.isCancelled(any())).thenReturn(false);
        when(callbacks.isTimedOut(any())).thenReturn(false);
        when(phaseExecutor.dispatchPhase(eq(phase1), anyString(), any(), any(), anyInt(), any(), any()))
                .thenReturn(Mono.just(PhaseResult.success("out1")));
        when(callbacks.createTokenContext(any(), any())).thenReturn(TokenContext.builder().build());
        when(callbacks.callLlm(anyString(), any()))
                .thenReturn(Mono.just(new LlmStrategy.LlmResult("synthesis", null)));

        PhaseResult result = strategy.execute(
                List.of(phase0, phase1), ctx, executionId, accountId, callbacks, 1).block();

        assertNotNull(result);
        assertFalse(result.paused());
        verify(phaseExecutor, times(1)).dispatchPhase(any(), anyString(), any(), any(), anyInt(), any(), any());
    }
}
