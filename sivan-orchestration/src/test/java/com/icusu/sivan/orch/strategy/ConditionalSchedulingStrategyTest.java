package com.icusu.sivan.orch.strategy;

import com.icusu.sivan.common.enums.SquadMode;
import com.icusu.sivan.agent.model.ModelRouter;
import com.icusu.sivan.core.message.Msg;
import com.icusu.sivan.core.message.Role;
import com.icusu.sivan.core.model.Model;
import com.icusu.sivan.domain.orchestration.ContextPackage;
import com.icusu.sivan.domain.orchestration.ISquadExecutionRepository;
import com.icusu.sivan.domain.orchestration.PhaseNode;
import com.icusu.sivan.orch.executor.GroupMode;
import com.icusu.sivan.orch.executor.PhaseCallbacks;
import com.icusu.sivan.orch.executor.PhaseExecutor;
import com.icusu.sivan.orch.executor.PhaseResult;
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
class ConditionalSchedulingStrategyTest {

    @Mock private PhaseExecutor phaseExecutor;
    @Mock private ModelRouter modelRouter;
    @Mock private ISquadExecutionRepository squadExecutionRepository;

    private ConditionalSchedulingStrategy strategy;
    private final UUID executionId = UUID.randomUUID();
    private final UUID accountId = UUID.randomUUID();

    private final PhaseNode phase0 = PhaseNode.builder().phase(0).name("分析").agents(List.of("a1")).build();
    private final PhaseNode phase1 = PhaseNode.builder().phase(1).name("执行").agents(List.of("a2")).build();

    @BeforeEach
    void setUp() {
        strategy = new ConditionalSchedulingStrategy(phaseExecutor, modelRouter, squadExecutionRepository);
    }

    @Test
    void supportedMode_shouldReturnConditional() {
        assertEquals(SquadMode.CONDITIONAL, strategy.supportedMode());
    }

    @Test
    void schedule_shouldReturnOneGroupPerPhase() {
        var plan = strategy.schedule(List.of(phase0, phase1));
        assertEquals(2, plan.getGroups().size());
        plan.getGroups().forEach(g -> assertEquals(GroupMode.SEQUENTIAL, g.getMode()));
        assertEquals(1, plan.getGroups().get(0).getPhases().size());
        assertEquals(1, plan.getGroups().get(1).getPhases().size());
    }

    @Test
    void execute_shouldRunSinglePhase_whenLastPhase() {
        PhaseCallbacks callbacks = mock(PhaseCallbacks.class);
        ContextPackage ctx = new ContextPackage("input", "task", Map.of(), null, null);

        when(phaseExecutor.dispatchPhase(eq(phase0), anyString(), any(), any(), anyInt(), any(), any()))
                .thenReturn(Mono.just(PhaseResult.success("output")));
        when(callbacks.isCancelled(any())).thenReturn(false);
        when(callbacks.isTimedOut(any())).thenReturn(false);

        PhaseResult result = strategy.execute(List.of(phase0), ctx, executionId, accountId, callbacks, 0).block();

        assertNotNull(result);
        assertFalse(result.paused());
        assertEquals("output", result.content());
    }

    @Test
    void execute_shouldRouteToNextPhase_whenLlmReturnsValidIndex() {
        PhaseCallbacks callbacks = mock(PhaseCallbacks.class);
        ContextPackage ctx = new ContextPackage("input", "task", Map.of(), null, null);
        Model model = mock(Model.class);

        when(modelRouter.getDefaultModel(accountId)).thenReturn(model);
        when(model.chat(anyList(), any())).thenReturn(Mono.just(
                new Model.ModelResponse(Msg.of(Role.ASSISTANT, "1"), null)));
        when(phaseExecutor.dispatchPhase(eq(phase0), anyString(), any(), any(), anyInt(), any(), any()))
                .thenReturn(Mono.just(PhaseResult.success("out0")));
        when(phaseExecutor.dispatchPhase(eq(phase1), anyString(), any(), any(), anyInt(), any(), any()))
                .thenReturn(Mono.just(PhaseResult.success("out1")));
        when(callbacks.isCancelled(any())).thenReturn(false);
        when(callbacks.isTimedOut(any())).thenReturn(false);

        PhaseResult result = strategy.execute(List.of(phase0, phase1), ctx, executionId, accountId, callbacks, 0).block();

        assertNotNull(result);
        assertFalse(result.paused());
        verify(phaseExecutor, times(2)).dispatchPhase(any(), anyString(), any(), any(), anyInt(), any(), any());
    }

    @Test
    void execute_shouldComplete_whenLlmReturnsNegativeOne() {
        PhaseCallbacks callbacks = mock(PhaseCallbacks.class);
        ContextPackage ctx = new ContextPackage("input", "task", Map.of(), null, null);
        Model model = mock(Model.class);

        when(modelRouter.getDefaultModel(accountId)).thenReturn(model);
        when(model.chat(anyList(), any())).thenReturn(Mono.just(
                new Model.ModelResponse(Msg.of(Role.ASSISTANT, "-1"), null)));
        when(phaseExecutor.dispatchPhase(eq(phase0), anyString(), any(), any(), anyInt(), any(), any()))
                .thenReturn(Mono.just(PhaseResult.success("out0")));
        when(callbacks.isCancelled(any())).thenReturn(false);
        when(callbacks.isTimedOut(any())).thenReturn(false);

        PhaseResult result = strategy.execute(List.of(phase0, phase1), ctx, executionId, accountId, callbacks, 0).block();

        assertNotNull(result);
        assertFalse(result.paused());
        // phase1 should NOT be executed (LLM said done)
        verify(phaseExecutor, times(1)).dispatchPhase(any(), anyString(), any(), any(), anyInt(), any(), any());
    }

    @Test
    void execute_shouldPause_whenCancelled() {
        PhaseCallbacks callbacks = mock(PhaseCallbacks.class);
        when(callbacks.isCancelled(any())).thenReturn(true);
        ContextPackage ctx = new ContextPackage("input", "task", Map.of(), null, null);

        PhaseResult result = strategy.execute(List.of(phase0), ctx, executionId, accountId, callbacks, 0).block();

        assertTrue(result.paused());
    }

    @Test
    void execute_shouldPause_whenTimedOut() {
        PhaseCallbacks callbacks = mock(PhaseCallbacks.class);
        when(callbacks.isTimedOut(any())).thenReturn(true);
        ContextPackage ctx = new ContextPackage("input", "task", Map.of(), null, null);

        PhaseResult result = strategy.execute(List.of(phase0), ctx, executionId, accountId, callbacks, 0).block();

        assertTrue(result.paused());
    }

    @Test
    void execute_shouldHandleEmptyPhases() {
        PhaseCallbacks callbacks = mock(PhaseCallbacks.class);
        ContextPackage ctx = new ContextPackage("input", "task", Map.of(), null, null);

        PhaseResult result = strategy.execute(List.of(), ctx, executionId, accountId, callbacks, 0).block();

        assertNotNull(result);
        assertFalse(result.paused());
    }
}
