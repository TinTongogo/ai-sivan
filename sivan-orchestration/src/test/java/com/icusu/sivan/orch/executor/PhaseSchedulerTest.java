package com.icusu.sivan.orch.executor;

import com.icusu.sivan.orch.strategy.*;
import com.icusu.sivan.agent.model.ModelRouter;
import com.icusu.sivan.common.enums.SquadMode;
import com.icusu.sivan.domain.orchestration.ISquadExecutionRepository;
import com.icusu.sivan.domain.orchestration.PhaseNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PhaseSchedulerTest {

    @Mock
    private PhaseExecutor phaseExecutor;
    @Mock
    private ModelRouter modelRouter;
    @Mock
    private ISquadExecutionRepository squadExecutionRepository;

    private PhaseScheduler scheduler;
    private final UUID executionId = UUID.randomUUID();
    private final UUID accountId = UUID.randomUUID();

    private final PhaseNode phase0 = PhaseNode.builder().phase(0).name("规划").agents(List.of("manager")).description("规划阶段").build();
    private final PhaseNode phase1 = PhaseNode.builder().phase(1).name("执行").agents(List.of("worker1")).description("执行阶段").build();
    private final PhaseNode phase2 = PhaseNode.builder().phase(2).name("审查").agents(List.of("worker2")).description("审查阶段").build();

    @BeforeEach
    void setUp() {
        var strategies = List.of(new SequentialSchedulingStrategy(phaseExecutor, squadExecutionRepository),
                new ParallelSchedulingStrategy(phaseExecutor, squadExecutionRepository),
                new ConditionalSchedulingStrategy(phaseExecutor, modelRouter, squadExecutionRepository),
                new HierarchicalSchedulingStrategy(phaseExecutor), new ConsensusSchedulingStrategy(phaseExecutor));
        scheduler = new PhaseScheduler(strategies);
    }

    // ========== schedule() ==========

    @Test
    void scheduleSequential_oneSequentialGroup() {
        SchedulePlan plan = scheduler.schedule(List.of(phase0, phase1, phase2), SquadMode.SEQUENTIAL);
        assertEquals(1, plan.getGroups().size());
        assertEquals(GroupMode.SEQUENTIAL, plan.getGroups().get(0).getMode());
        assertEquals(3, plan.getGroups().get(0).getPhases().size());
    }

    @Test
    void scheduleParallel_oneParallelGroup() {
        SchedulePlan plan = scheduler.schedule(List.of(phase0, phase1, phase2), SquadMode.PARALLEL);
        assertEquals(1, plan.getGroups().size());
        assertEquals(GroupMode.PARALLEL, plan.getGroups().get(0).getMode());
        assertEquals(3, plan.getGroups().get(0).getPhases().size());
    }

    @Test
    void scheduleConditional_eachPhaseInOwnSequentialGroup() {
        SchedulePlan plan = scheduler.schedule(List.of(phase0, phase1), SquadMode.CONDITIONAL);
        assertEquals(2, plan.getGroups().size());
        plan.getGroups().forEach(g -> assertEquals(GroupMode.SEQUENTIAL, g.getMode()));
        assertEquals(1, plan.getGroups().get(0).getPhases().size());
        assertEquals(1, plan.getGroups().get(1).getPhases().size());
        assertEquals(0, plan.getGroups().get(0).getPhases().get(0).getPhase());
        assertEquals(1, plan.getGroups().get(1).getPhases().get(0).getPhase());
    }

    @Test
    void scheduleHierarchical_planningThenExecution() {
        SchedulePlan plan = scheduler.schedule(List.of(phase0, phase1, phase2), SquadMode.HIERARCHICAL);
        assertEquals(2, plan.getGroups().size());
        assertEquals(GroupMode.SEQUENTIAL, plan.getGroups().get(0).getMode()); // planning
        assertEquals(1, plan.getGroups().get(0).getPhases().size());
        assertEquals("规划", plan.getGroups().get(0).getPhases().get(0).getName());
        assertEquals(GroupMode.PARALLEL, plan.getGroups().get(1).getMode()); // execution
        assertEquals(2, plan.getGroups().get(1).getPhases().size());
    }

    @Test
    void scheduleHierarchical_singlePhase_noExecutionGroup() {
        SchedulePlan plan = scheduler.schedule(List.of(phase0), SquadMode.HIERARCHICAL);
        assertEquals(1, plan.getGroups().size());
        assertEquals(GroupMode.SEQUENTIAL, plan.getGroups().get(0).getMode());
    }

    @Test
    void scheduleConsensus_oneParallelGroup() {
        SchedulePlan plan = scheduler.schedule(List.of(phase0, phase1), SquadMode.CONSENSUS);
        assertEquals(1, plan.getGroups().size());
        assertEquals(GroupMode.PARALLEL, plan.getGroups().get(0).getMode());
        assertEquals(2, plan.getGroups().get(0).getPhases().size());
    }

    @Test
    void scheduleEmptyPhases_returnsPlanWithEmptyGroups() {
        SchedulePlan plan = scheduler.schedule(List.of(), SquadMode.SEQUENTIAL);
        assertFalse(plan.isEmpty()); // always at least one SEQUENTIAL group
        assertTrue(plan.getGroups().get(0).getPhases().isEmpty());
    }

    // ========== executeSquad() ==========

    @Test
    void executeSquadSequential_delegatesToPhaseExecutor() {
        PhaseCallbacks callbacks = mockPhaseCallbacks();
        List<PhaseNode> phases = List.of(phase0, phase1);

        when(phaseExecutor.dispatchPhase(eq(phase0), anyString(), any(), any(), anyInt(), any(), any())).thenReturn(Mono.just(PhaseResult.success("phase0_output")));
        when(phaseExecutor.dispatchPhase(eq(phase1), anyString(), any(), any(), anyInt(), any(), any())).thenReturn(Mono.just(PhaseResult.success("phase1_output")));

        PhaseResult result = scheduler.executeSquad(phases, SquadMode.SEQUENTIAL, "input", executionId, accountId, callbacks).block();

        assertFalse(result.paused());
        assertTrue(result.content().contains("phase1_output"));
    }

    private PhaseCallbacks mockPhaseCallbacks() {
        return new PhaseCallbacks() {
            @Override
            public Mono<PhaseCallbacks.LlmResult> callLlm(String userMessage, com.icusu.sivan.domain.shared.vo.TokenContext ctx) {
                return Mono.just(new PhaseCallbacks.LlmResult("mock", ""));
            }

            @Override
            public void saveContract(UUID execId, UUID acctId, int phaseIndex, String sourceAgent, String targetAgent, String content) {
            }

            @Override
            public com.icusu.sivan.domain.shared.vo.TokenContext createTokenContext(UUID acctId, UUID execId) {
                return com.icusu.sivan.domain.shared.vo.TokenContext.builder().accountId(acctId).build();
            }
        };
    }
}
