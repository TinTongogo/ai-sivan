package com.icusu.sivan.orch.executor;

import com.icusu.sivan.common.enums.SquadMode;
import com.icusu.sivan.orch.strategy.*;
import com.icusu.sivan.domain.orchestration.PhaseNode;
import com.icusu.sivan.domain.shared.vo.TokenContext;
import com.icusu.sivan.infra.knowledge.EmbeddingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PhaseExecutorTest {

    @Mock
    private EmbeddingService embeddingService;
    @Mock
    private SquadPipelineAdapter squadPipelineAdapter;
    @Mock(answer = Answers.CALLS_REAL_METHODS)
    private PhaseCallbacks callbacks;

    private PhaseExecutor executor;
    private ConditionalExecutionStrategy conditionalStrategy;
    private ConsensusExecutionStrategy consensusStrategy;
    private HierarchicalExecutionStrategy hierarchicalStrategy;
    private final UUID executionId = UUID.randomUUID();
    private final UUID accountId = UUID.randomUUID();
    private final TokenContext tokenCtx = TokenContext.builder()
            .accountId(accountId).build();

    /** 正交向量（Qwen3 2048 维，各方向正交，cosine similarity 可精确控制）。 */
    private static final float[] VEC_X = new float[2048];
    private static final float[] VEC_Y = new float[2048];
    static {
        VEC_X[0] = 1;
        VEC_Y[1] = 1;
    }

    @BeforeEach
    void setUp() {
        conditionalStrategy = new ConditionalExecutionStrategy(embeddingService, squadPipelineAdapter);
        consensusStrategy = new ConsensusExecutionStrategy();
        hierarchicalStrategy = new HierarchicalExecutionStrategy();
        var strategies = List.of(
                new SequentialExecutionStrategy(squadPipelineAdapter),
                new ParallelExecutionStrategy(squadPipelineAdapter),
                conditionalStrategy,
                consensusStrategy,
                hierarchicalStrategy
        );
        executor = new PhaseExecutor(strategies);
        lenient().when(callbacks.createTokenContext(any(), any())).thenReturn(tokenCtx);
    }

    // ========== SEQUENTIAL ==========

    @Nested
    class SequentialTest {
        @Test
        void emptyAgents_returnsInput() {
            PhaseNode phase = PhaseNode.builder().agents(List.of()).build();
            PhaseResult result = executor.dispatchPhase(phase, "input", executionId, accountId, 0, callbacks, null).block();
            assertEquals("input", result.content());
            assertFalse(result.paused());
        }

        @Test
        void nullAgents_returnsInput() {
            PhaseNode phase = PhaseNode.builder().build();
            PhaseResult result = executor.dispatchPhase(phase, "input", executionId, accountId, 0, callbacks, null).block();
            assertEquals("input", result.content());
        }

        @Test
        void singleAgent_callsLlmOnce() {
            when(squadPipelineAdapter.executePhase(any(), anyString(), any(), any(), anyInt(), any(), anyList()))
                    .thenReturn(Mono.just(PhaseResult.success("output")));
            PhaseNode phase = PhaseNode.builder().agents(List.of("agent1")).description("test").build();

            PhaseResult result = executor.dispatchPhase(phase, "input", executionId, accountId, 0, callbacks, null).block();

            assertEquals("output", result.content());
            verify(squadPipelineAdapter).executePhase(eq(phase), eq("input"), eq(executionId), eq(accountId),
                    eq(0), eq(callbacks), anyList());
        }

        @Test
        void multipleAgents_chainsOutput() {
            when(squadPipelineAdapter.executePhase(any(), anyString(), any(), any(), anyInt(), any(), anyList()))
                    .thenReturn(Mono.just(PhaseResult.success("final")));
            PhaseNode phase = PhaseNode.builder()
                    .agents(List.of("agent1", "agent2")).description("test").build();

            PhaseResult result = executor.dispatchPhase(phase, "input", executionId, accountId, 0, callbacks, null).block();

            assertEquals("final", result.content());
            verify(squadPipelineAdapter).executePhase(eq(phase), eq("input"), eq(executionId), eq(accountId),
                    eq(0), eq(callbacks), anyList());
        }
    }

    // ========== PARALLEL ==========

    @Nested
    class ParallelTest {
        @Test
        void emptyAgents_returnsInput() {
            PhaseNode phase = PhaseNode.builder().agents(List.of()).build();
            PhaseResult result = executor.dispatchPhase(phase, "input", executionId, accountId, 0, callbacks, null).block();
            assertEquals("input", result.content());
        }

        @Test
        void multipleAgents_mergesResults() {
            when(squadPipelineAdapter.executePhase(any(), anyString(), any(), any(), anyInt(), any(), anyList()))
                    .thenReturn(Mono.just(PhaseResult.success("resultA\n\nresultB")));
            PhaseNode phase = PhaseNode.builder()
                    .agents(List.of("agentA", "agentB")).description("test").build();

            PhaseResult result = executor.dispatchPhase(phase, "input", executionId, accountId, 0, callbacks, null).block();

            assertTrue(result.content().contains("resultA"));
            assertTrue(result.content().contains("resultB"));
            verify(squadPipelineAdapter).executePhase(eq(phase), eq("input"), eq(executionId), eq(accountId),
                    eq(0), eq(callbacks), anyList());
        }
    }

    // ========== CONDITIONAL ==========

    @Nested
    class ConditionalTest {

        @Test
        void noFilter_executesSequentially() {
            PhaseNode phase = PhaseNode.builder()
                    .agents(List.of("agent1")).description("test").build();
            when(squadPipelineAdapter.executePhase(any(), anyString(), any(), any(), anyInt(), any(), anyList()))
                    .thenReturn(Mono.just(PhaseResult.success("output")));

            PhaseResult result = conditionalStrategy.execute(phase, "input", executionId, accountId, 0, callbacks, List.of()).block();

            assertEquals("output", result.content());
            verify(squadPipelineAdapter).executePhase(eq(phase), eq("input"), eq(executionId), eq(accountId),
                    eq(0), eq(callbacks), anyList());
        }

        @Test
        void embeddingMatchAboveThreshold_executes() {
            when(embeddingService.embed("安全漏洞修复任务")).thenReturn(VEC_X);
            when(embeddingService.embed("security")).thenReturn(VEC_X);
            when(squadPipelineAdapter.executePhase(any(), anyString(), any(), any(), anyInt(), any(), anyList()))
                    .thenReturn(Mono.just(PhaseResult.success("output")));

            PhaseNode phase = PhaseNode.builder()
                    .agents(List.of("agent1")).description("test")
                    .outputFilter("security").build();

            PhaseResult result = conditionalStrategy.execute(phase, "安全漏洞修复任务",
                    executionId, accountId, 0, callbacks, List.of()).block();

            assertEquals("output", result.content());
            verify(squadPipelineAdapter).executePhase(eq(phase), eq("安全漏洞修复任务"), eq(executionId),
                    eq(accountId), eq(0), eq(callbacks), anyList());
        }

        @Test
        void embeddingNoMatchBelowThreshold_skips() {
            when(embeddingService.embed("简单文本处理")).thenReturn(VEC_Y);
            when(embeddingService.embed("security")).thenReturn(VEC_X);

            PhaseNode phase = PhaseNode.builder()
                    .agents(List.of("agent1")).description("test")
                    .outputFilter("security").build();

            PhaseResult result = conditionalStrategy.execute(phase, "简单文本处理",
                    executionId, accountId, 0, callbacks, List.of()).block();

            assertEquals("简单文本处理", result.content());
            verify(squadPipelineAdapter, never()).executePhase(any(), anyString(), any(), any(),
                    anyInt(), any(), anyList());
        }

        @Test
        void embeddingFallsBackToKeywordMatch() {
            when(embeddingService.embed(anyString())).thenThrow(new RuntimeException("服务不可用"));
            when(squadPipelineAdapter.executePhase(any(), anyString(), any(), any(), anyInt(), any(), anyList()))
                    .thenReturn(Mono.just(PhaseResult.success("output")));

            PhaseNode phase1 = PhaseNode.builder()
                    .agents(List.of("agent1")).description("test")
                    .outputFilter("security").build();

            PhaseResult r1 = conditionalStrategy.execute(phase1, "fix security bug",
                    executionId, accountId, 0, callbacks, List.of()).block();
            assertEquals("output", r1.content());

            PhaseResult r2 = conditionalStrategy.execute(phase1, "just a text",
                    executionId, accountId, 0, callbacks, List.of()).block();
            assertEquals("just a text", r2.content());
        }
    }

    // ========== CONSENSUS ==========

    @Nested
    class ConsensusTest {

        @Test
        void emptyAgents_returnsInput() {
            PhaseNode phase = PhaseNode.builder().agents(List.of()).build();
            PhaseResult result = consensusStrategy.execute(phase, "input", executionId, accountId, 0, callbacks, List.of()).block();
            assertEquals("input", result.content());
            assertFalse(result.paused());
        }

        @Test
        void singleAgent_oneRoundOnly() {
            when(callbacks.callLlm(anyString(), any()))
                    .thenReturn(Mono.just(new PhaseCallbacks.LlmResult("单 Agent 输出", "")));

            PhaseNode phase = PhaseNode.builder()
                    .agents(List.of("agent1")).description("test").build();

            PhaseResult result = consensusStrategy.execute(phase, "input", executionId, accountId, 0, callbacks, List.of()).block();

            assertEquals("单 Agent 输出", result.content());
            assertFalse(result.paused());
            verify(callbacks, times(1)).callLlm(anyString(), any());
        }

        @Test
        void highConfidence_oneRoundComplete() {
            when(callbacks.callLlm(anyString(), any()))
                    .thenReturn(Mono.just(new PhaseCallbacks.LlmResult("agent1 answer", "")))
                    .thenReturn(Mono.just(new PhaseCallbacks.LlmResult("agent2 answer", "")))
                    .thenReturn(Mono.just(new PhaseCallbacks.LlmResult(
                            "{\"confidence\":0.85,\"majorityOpinion\":\"意见一致\",\"dissentPoints\":[],\"dissenters\":[],\"conclusion\":\"最终结论\"}",
                            "")));

            PhaseNode phase = PhaseNode.builder()
                    .agents(List.of("agent1", "agent2")).description("test").build();

            PhaseResult result = consensusStrategy.execute(phase, "input", executionId, accountId, 0, callbacks, List.of()).block();

            assertTrue(result.content().contains("0.85"));
            assertTrue(result.content().contains("最终结论"));
            assertFalse(result.paused());
            verify(callbacks, times(3)).callLlm(anyString(), any());
        }

        @Test
        void lowConfidence_secondRoundForDissenters() {
            when(callbacks.callLlm(anyString(), any()))
                    .thenReturn(Mono.just(new PhaseCallbacks.LlmResult("agent1 says yes", "")))
                    .thenReturn(Mono.just(new PhaseCallbacks.LlmResult("agent2 says no", "")))
                    .thenReturn(Mono.just(new PhaseCallbacks.LlmResult(
                            "{\"confidence\":0.45,\"majorityOpinion\":\"yes\",\"dissentPoints\":[\"意见分歧\"],\"dissenters\":[\"agent2\"],\"conclusion\":\"初步综合\"}",
                            "")))
                    .thenReturn(Mono.just(new PhaseCallbacks.LlmResult("agent2 reconsidered: maybe yes", "")))
                    .thenReturn(Mono.just(new PhaseCallbacks.LlmResult(
                            "{\"confidence\":0.82,\"majorityOpinion\":\"达成一致\",\"dissentPoints\":[],\"dissenters\":[],\"conclusion\":\"最终共识\"}",
                            "")));

            PhaseNode phase = PhaseNode.builder()
                    .agents(List.of("agent1", "agent2")).description("test").build();

            PhaseResult result = consensusStrategy.execute(phase, "input", executionId, accountId, 0, callbacks, List.of()).block();

            assertTrue(result.content().contains("0.82"));
            assertTrue(result.content().contains("最终共识"));
            assertFalse(result.paused());
            verify(callbacks, times(5)).callLlm(anyString(), any());
        }

        @Test
        void stillLowAfterSecondRound_hitlFallback() {
            when(callbacks.callLlm(anyString(), any()))
                    .thenReturn(Mono.just(new PhaseCallbacks.LlmResult("opinion A", "")))
                    .thenReturn(Mono.just(new PhaseCallbacks.LlmResult("opinion B", "")))
                    .thenReturn(Mono.just(new PhaseCallbacks.LlmResult(
                            "{\"confidence\":0.30,\"majorityOpinion\":\"A\",\"dissentPoints\":[\"严重分歧\"],\"dissenters\":[\"agent2\"],\"conclusion\":\"无法达成一致\"}",
                            "")))
                    .thenReturn(Mono.just(new PhaseCallbacks.LlmResult("still B", "")))
                    .thenReturn(Mono.just(new PhaseCallbacks.LlmResult(
                            "{\"confidence\":0.35,\"majorityOpinion\":\"A\",\"dissentPoints\":[\"严重分歧\"],\"dissenters\":[\"agent2\"],\"conclusion\":\"仍无法达成一致\"}",
                            "")));

            PhaseNode phase = PhaseNode.builder()
                    .agents(List.of("agent1", "agent2"))
                    .name("consensus-phase").description("test").build();

            PhaseResult result = consensusStrategy.execute(phase, "input", executionId, accountId, 0, callbacks, List.of()).block();

            assertTrue(result.paused());
            assertTrue(result.content().contains("CONSENSUS"));
            verify(callbacks).publishEvent(executionId, "HITL_PENDING", 0, "consensus-phase",
                    "CONSENSUS 未达成共识，需人工决策");
        }
    }

    // ========== HIERARCHICAL ==========

    @Nested
    class HierarchicalTest {

        @Test
        void emptyAgents_returnsInput() {
            PhaseNode phase = PhaseNode.builder().agents(List.of()).build();
            PhaseResult result = hierarchicalStrategy.execute(phase, "input", executionId, accountId, 0, callbacks, List.of()).block();
            assertEquals("input", result.content());
        }

        @Test
        void singleAgent_managerOnly() {
            when(callbacks.callLlm(anyString(), any()))
                    .thenReturn(Mono.just(new PhaseCallbacks.LlmResult("decomposition text", "")));

            PhaseNode phase = PhaseNode.builder()
                    .agents(List.of("manager")).description("test").build();

            PhaseResult result = hierarchicalStrategy.execute(phase, "input", executionId, accountId, 0, callbacks, List.of()).block();

            assertTrue(result.content().contains("decomposition text"));
            verify(callbacks, times(1)).callLlm(anyString(), any());
        }

        @Test
        void validJson_parsesSubTasksAndExecutesInOrder() {
            String subtaskJson = "{\"subtasks\":[{\"id\":1,\"goal\":\"task1\",\"input\":\"do 1\",\"expected_output\":\"result1\",\"depends_on\":[]},{\"id\":2,\"goal\":\"task2\",\"input\":\"do 2\",\"expected_output\":\"result2\",\"depends_on\":[1]}]}";

            when(callbacks.callLlm(anyString(), any()))
                    .thenReturn(Mono.just(new PhaseCallbacks.LlmResult(subtaskJson, "")))
                    .thenReturn(Mono.just(new PhaseCallbacks.LlmResult("subtask1 output", "")))
                    .thenReturn(Mono.just(new PhaseCallbacks.LlmResult("subtask2 output", "")));

            PhaseNode phase = PhaseNode.builder()
                    .agents(List.of("manager", "worker1", "worker2")).description("test").build();

            PhaseResult result = hierarchicalStrategy.execute(phase, "input", executionId, accountId, 0, callbacks, List.of()).block();

            assertTrue(result.content().contains("子任务 1"));
            assertTrue(result.content().contains("子任务 2"));
            assertTrue(result.content().contains("subtask1 output"));
            assertTrue(result.content().contains("subtask2 output"));
            verify(callbacks, times(3)).callLlm(anyString(), any());
        }

        @Test
        void independentSubTasks_runInParallel() {
            String subtaskJson = "{\"subtasks\":[{\"id\":1,\"goal\":\"task1\",\"input\":\"do 1\",\"expected_output\":\"r1\",\"depends_on\":[]},{\"id\":2,\"goal\":\"task2\",\"input\":\"do 2\",\"expected_output\":\"r2\",\"depends_on\":[]}]}";

            when(callbacks.callLlm(anyString(), any()))
                    .thenReturn(Mono.just(new PhaseCallbacks.LlmResult(subtaskJson, "")))
                    .thenReturn(Mono.just(new PhaseCallbacks.LlmResult("output1", "")))
                    .thenReturn(Mono.just(new PhaseCallbacks.LlmResult("output2", "")));

            PhaseNode phase = PhaseNode.builder()
                    .agents(List.of("manager", "worker")).description("test").build();

            PhaseResult result = hierarchicalStrategy.execute(phase, "input", executionId, accountId, 0, callbacks, List.of()).block();

            assertTrue(result.content().contains("output1"));
            assertTrue(result.content().contains("output2"));
        }

        @Test
        void invalidJson_fallsBackToSequential() {
            when(callbacks.callLlm(anyString(), any()))
                    .thenReturn(Mono.just(new PhaseCallbacks.LlmResult("自然语言分解说明", "")))
                    .thenReturn(Mono.just(new PhaseCallbacks.LlmResult("worker output", "")));

            PhaseNode phase = PhaseNode.builder()
                    .agents(List.of("manager", "worker")).description("test").build();

            PhaseResult result = hierarchicalStrategy.execute(phase, "input", executionId, accountId, 0, callbacks, List.of()).block();

            assertTrue(result.content().contains("自然语言分解说明"));
            assertTrue(result.content().contains("worker output"));
            verify(callbacks, times(2)).callLlm(anyString(), any());
        }

        @Test
        void multipleWorkers_eachReceivesSubtaskContext() {
            String subtaskJson = "{\"subtasks\":[{\"id\":1,\"goal\":\"taskA\",\"input\":\"context A\",\"expected_output\":\"rA\",\"depends_on\":[]}]}";

            when(callbacks.callLlm(anyString(), any()))
                    .thenReturn(Mono.just(new PhaseCallbacks.LlmResult(subtaskJson, "")))
                    .thenReturn(Mono.just(new PhaseCallbacks.LlmResult("outputA", "")));

            PhaseNode phase = PhaseNode.builder()
                    .agents(List.of("manager", "worker1")).description("test").build();

            hierarchicalStrategy.execute(phase, "input", executionId, accountId, 0, callbacks, List.of()).block();

            verify(callbacks).callLlm(contains("taskA"), any());
        }
    }

    // ========== dispatchPhase ==========

    @Nested
    class DispatchTest {
        @Test
        void dispatchByMode() {
            when(squadPipelineAdapter.executePhase(any(), anyString(), any(), any(), anyInt(), any(), anyList()))
                    .thenReturn(Mono.just(PhaseResult.success("output")))
                    .thenReturn(Mono.just(PhaseResult.success("output")));

            PhaseNode seq = PhaseNode.builder().agents(List.of("a")).mode(SquadMode.SEQUENTIAL).build();
            PhaseNode par = PhaseNode.builder().agents(List.of("a")).mode(SquadMode.PARALLEL).build();

            assertNotNull(executor.dispatchPhase(seq, "in", executionId, accountId, 0, callbacks, null).block());
            assertFalse(executor.dispatchPhase(seq, "in", executionId, accountId, 0, callbacks, null).block().paused());
            assertNotNull(executor.dispatchPhase(par, "in", executionId, accountId, 0, callbacks, null).block());
        }

        @Test
        void unknownMode_fallsBackToSequential() {
            when(squadPipelineAdapter.executePhase(any(), anyString(), any(), any(), anyInt(), any(), anyList()))
                    .thenReturn(Mono.just(PhaseResult.success("output")));

            PhaseNode unknown = PhaseNode.builder().agents(List.of("a")).mode(null).build();
            PhaseResult result = executor.dispatchPhase(unknown, "in", executionId, accountId, 0, callbacks, null).block();
            assertEquals("output", result.content());
        }

        @Test
        void hitlTrue_pausesAfterExecution() {
            when(squadPipelineAdapter.executePhase(any(), anyString(), any(), any(), anyInt(), any(), anyList()))
                    .thenReturn(Mono.just(PhaseResult.success("output")));

            PhaseNode phase = PhaseNode.builder()
                    .agents(List.of("a")).description("test").mode(SquadMode.SEQUENTIAL)
                    .hitlMode("POST").name("review-phase").build();

            PhaseResult result = executor.dispatchPhase(phase, "input", executionId, accountId, 0, callbacks, null).block();

            assertTrue(result.paused());
            assertTrue(result.pauseReason().contains("review-phase"));
            verify(callbacks).publishEvent(executionId, "HITL_PENDING", 0, "review-phase",
                    "阶段完成，需人工审核");
        }
    }
}
