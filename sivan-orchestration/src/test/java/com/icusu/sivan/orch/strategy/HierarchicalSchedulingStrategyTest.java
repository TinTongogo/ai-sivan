package com.icusu.sivan.orch.strategy;

import com.icusu.sivan.common.enums.SquadMode;
import com.icusu.sivan.domain.orchestration.ContextPackage;
import com.icusu.sivan.domain.orchestration.PhaseNode;
import com.icusu.sivan.orch.executor.GroupMode;
import com.icusu.sivan.orch.executor.PhaseCallbacks;
import com.icusu.sivan.orch.executor.PhaseExecutor;
import com.icusu.sivan.orch.executor.PhaseResult;
import com.icusu.sivan.orch.executor.ScheduleGroup;
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
class HierarchicalSchedulingStrategyTest {

    @Mock private PhaseExecutor phaseExecutor;

    private HierarchicalSchedulingStrategy strategy;
    private final UUID executionId = UUID.randomUUID();
    private final UUID accountId = UUID.randomUUID();

    private final PhaseNode planPhase = PhaseNode.builder()
            .phase(0).name("规划").agents(List.of("manager")).description("规划").build();
    private final PhaseNode execPhase = PhaseNode.builder()
            .phase(1).name("执行").agents(List.of("worker")).description("执行").build();

    @BeforeEach
    void setUp() {
        strategy = new HierarchicalSchedulingStrategy(phaseExecutor);
    }

    @Test
    void supportedMode_shouldReturnHierarchical() {
        assertEquals(SquadMode.HIERARCHICAL, strategy.supportedMode());
    }

    @Test
    void schedule_shouldReturnPlanningAndExecutionGroups() {
        var plan = strategy.schedule(List.of(planPhase, execPhase));

        assertEquals(2, plan.getGroups().size());

        ScheduleGroup planning = plan.getGroups().get(0);
        assertEquals(GroupMode.SEQUENTIAL, planning.getMode());
        assertEquals(1, planning.getPhases().size());
        assertEquals(planPhase, planning.getPhases().get(0));

        ScheduleGroup execution = plan.getGroups().get(1);
        assertEquals(GroupMode.PARALLEL, execution.getMode());
        assertEquals(1, execution.getPhases().size());
        assertEquals(execPhase, execution.getPhases().get(0));
    }

    @Test
    void schedule_singlePhase_shouldReturnOnlyPlanningGroup() {
        var plan = strategy.schedule(List.of(planPhase));

        assertEquals(1, plan.getGroups().size());
        assertEquals(GroupMode.SEQUENTIAL, plan.getGroups().get(0).getMode());
        assertEquals(1, plan.getGroups().get(0).getPhases().size());
        assertEquals(planPhase, plan.getGroups().get(0).getPhases().get(0));
    }

    @Test
    void schedule_nullPhases_shouldReturnEmptyPlan() {
        var plan = strategy.schedule(null);
        assertTrue(plan.isEmpty());
    }

    @Test
    void execute_shouldRunPhasesSequentially() {
        PhaseCallbacks callbacks = mock(PhaseCallbacks.class);
        ContextPackage ctx = new ContextPackage("input", "task", Map.of(), null, null);

        when(callbacks.isCancelled(any())).thenReturn(false);
        when(callbacks.isTimedOut(any())).thenReturn(false);
        when(phaseExecutor.dispatchPhase(eq(planPhase), anyString(), any(), any(), anyInt(), any(), any()))
                .thenReturn(Mono.just(PhaseResult.success("plan_result")));
        when(phaseExecutor.dispatchPhase(eq(execPhase), anyString(), any(), any(), anyInt(), any(), any()))
                .thenReturn(Mono.just(PhaseResult.success("exec_result")));

        PhaseResult result = strategy.execute(List.of(planPhase, execPhase), ctx,
                executionId, accountId, callbacks, 0).block();

        assertNotNull(result);
        assertFalse(result.paused());
        verify(phaseExecutor, times(2)).dispatchPhase(any(), anyString(), any(), any(), anyInt(), any(), any());
        verify(callbacks, times(2)).onPhaseCompleted(any(), anyInt(), anyString(), anyLong());
    }

    @Test
    void execute_shouldPause_whenCancelled() {
        PhaseCallbacks callbacks = mock(PhaseCallbacks.class);
        when(callbacks.isCancelled(any())).thenReturn(true);
        ContextPackage ctx = new ContextPackage("input", "task", Map.of(), null, null);

        PhaseResult result = strategy.execute(List.of(planPhase, execPhase), ctx,
                executionId, accountId, callbacks, 0).block();

        assertTrue(result.paused());
        assertEquals("CANCELLED", result.pauseReason());
        assertEquals("input", result.content());
        verify(phaseExecutor, never()).dispatchPhase(any(), anyString(), any(), any(), anyInt(), any(), any());
    }

    @Test
    void execute_shouldHandleEmptyPhases() {
        PhaseCallbacks callbacks = mock(PhaseCallbacks.class);
        ContextPackage ctx = new ContextPackage("input", "task", Map.of(), null, null);

        PhaseResult result = strategy.execute(List.of(), ctx,
                executionId, accountId, callbacks, 0).block();

        assertNotNull(result);
        assertFalse(result.paused());
        assertEquals("input", result.content());
    }

    @Test
    void execute_shouldPause_whenPlanningPhasePaused() {
        PhaseCallbacks callbacks = mock(PhaseCallbacks.class);
        ContextPackage ctx = new ContextPackage("input", "task", Map.of(), null, null);

        when(callbacks.isCancelled(any())).thenReturn(false);
        when(callbacks.isTimedOut(any())).thenReturn(false);
        when(phaseExecutor.dispatchPhase(eq(planPhase), anyString(), any(), any(), anyInt(), any(), any()))
                .thenReturn(Mono.just(PhaseResult.paused("partial", "HITL_PRE")));

        PhaseResult result = strategy.execute(List.of(planPhase, execPhase), ctx,
                executionId, accountId, callbacks, 0).block();

        assertTrue(result.paused());
        assertEquals("HITL_PRE", result.pauseReason());
        verify(callbacks).onPhasePaused(eq(planPhase), eq(0), anyString(), any());
        verify(phaseExecutor, never()).dispatchPhase(eq(execPhase), anyString(), any(), any(), anyInt(), any(), any());
    }

    @Test
    void execute_shouldHandleTimeout_planningPhase() {
        PhaseCallbacks callbacks = mock(PhaseCallbacks.class);
        ContextPackage ctx = new ContextPackage("input", "task", Map.of(), null, null);

        when(callbacks.isCancelled(any())).thenReturn(false);
        when(callbacks.isTimedOut(any())).thenReturn(true);

        PhaseResult result = strategy.execute(List.of(planPhase), ctx,
                executionId, accountId, callbacks, 0).block();

        assertTrue(result.paused());
        assertEquals("TIMEOUT", result.pauseReason());
        verify(phaseExecutor, never()).dispatchPhase(any(), anyString(), any(), any(), anyInt(), any(), any());
    }

    @Test
    void execute_shouldSkipPlanning_whenStartPhaseGreaterThanZero() {
        PhaseCallbacks callbacks = mock(PhaseCallbacks.class);
        ContextPackage ctx = new ContextPackage("input", "task", Map.of(), null, null);

        when(callbacks.isCancelled(any())).thenReturn(false);
        when(callbacks.isTimedOut(any())).thenReturn(false);
        when(phaseExecutor.dispatchPhase(eq(execPhase), anyString(), any(), any(), anyInt(), any(), any()))
                .thenReturn(Mono.just(PhaseResult.success("exec_result")));

        PhaseResult result = strategy.execute(List.of(planPhase, execPhase), ctx,
                executionId, accountId, callbacks, 1).block();

        assertNotNull(result);
        assertFalse(result.paused());
        verify(phaseExecutor, times(1)).dispatchPhase(any(), anyString(), any(), any(), anyInt(), any(), any());
        verify(phaseExecutor, never()).dispatchPhase(eq(planPhase), anyString(), any(), any(), anyInt(), any(), any());
    }
}
