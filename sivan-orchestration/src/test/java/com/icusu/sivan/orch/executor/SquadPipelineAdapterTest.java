package com.icusu.sivan.orch.executor;

import com.icusu.sivan.common.enums.SquadMode;
import com.icusu.sivan.domain.orchestration.PhaseNode;
import com.icusu.sivan.domain.shared.vo.TokenContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SquadPipelineAdapterTest {

    @Mock private PhaseCallbacks callbacks;

    private SquadPipelineAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new SquadPipelineAdapter();
    }

    @Test
    void executePhase_无Agent返回成功() {
        PhaseNode phase = PhaseNode.builder().phase(0).agents(List.of()).build();

        PhaseResult result = adapter.executePhase(phase, "input",
                UUID.randomUUID(), UUID.randomUUID(), 0, callbacks, List.of()).block();

        assertNotNull(result);
        assertFalse(result.paused());
        assertEquals("input", result.content());
        verifyNoInteractions(callbacks);
    }

    @Test
    void executePhase_nullAgent返回成功() {
        PhaseNode phase = PhaseNode.builder().phase(0).build();

        PhaseResult result = adapter.executePhase(phase, "input",
                UUID.randomUUID(), UUID.randomUUID(), 0, callbacks, List.of()).block();

        assertNotNull(result);
        assertFalse(result.paused());
    }

    @Test
    void executePhase_SEQUENTIAL模式执行Agent链() {
        PhaseNode phase = PhaseNode.builder()
                .phase(0)
                .agents(List.of("agent1", "agent2"))
                .mode(SquadMode.SEQUENTIAL)
                .build();

        when(callbacks.createTokenContext(any(), any())).thenReturn(mock(TokenContext.class));
        when(callbacks.callReactAgent(eq("agent1"), anyString(), any()))
                .thenReturn(Mono.just(new LlmStrategy.LlmResult("中间结果", null)));
        when(callbacks.callReactAgent(eq("agent2"), anyString(), any()))
                .thenReturn(Mono.just(new LlmStrategy.LlmResult("最终结果", null)));

        PhaseResult result = adapter.executePhase(phase, "初始输入",
                UUID.randomUUID(), UUID.randomUUID(), 0, callbacks, List.of()).block();

        assertNotNull(result);
        assertFalse(result.paused());
        verify(callbacks, times(2)).callReactAgent(anyString(), anyString(), any());
        verify(callbacks).saveAgentCheckpoints(any(), anyInt(), anyList());
    }

    @Test
    void executePhase_PARALLEL模式执行Agent() {
        PhaseNode phase = PhaseNode.builder()
                .phase(0)
                .agents(List.of("agent-a", "agent-b"))
                .mode(SquadMode.PARALLEL)
                .build();

        when(callbacks.createTokenContext(any(), any())).thenReturn(mock(TokenContext.class));
        when(callbacks.callReactAgent(eq("agent-a"), anyString(), any()))
                .thenReturn(Mono.just(new LlmStrategy.LlmResult("输出A", null)));
        when(callbacks.callReactAgent(eq("agent-b"), anyString(), any()))
                .thenReturn(Mono.just(new LlmStrategy.LlmResult("输出B", null)));

        PhaseResult result = adapter.executePhase(phase, "输入",
                UUID.randomUUID(), UUID.randomUUID(), 0, callbacks, List.of()).block();

        assertNotNull(result);
        assertFalse(result.paused());
        assertTrue(result.content().contains("输出A"));
        assertTrue(result.content().contains("输出B"));
        verify(callbacks, times(2)).callReactAgent(anyString(), anyString(), any());
    }

    @Test
    void executePhase_SEQUENTIAL检查点恢复() {
        PhaseNode phase = PhaseNode.builder()
                .phase(0)
                .agents(List.of("agent1", "agent2"))
                .mode(SquadMode.SEQUENTIAL)
                .build();

        List<AgentCheckpoint> checkpoints = List.of(
                new AgentCheckpoint(0, "agent1", "COMPLETED", "已完成的输出", null));

        when(callbacks.createTokenContext(any(), any())).thenReturn(mock(TokenContext.class));
        when(callbacks.callReactAgent(eq("agent2"), anyString(), any()))
                .thenReturn(Mono.just(new LlmStrategy.LlmResult("最终", null)));

        PhaseResult result = adapter.executePhase(phase, "初始",
                UUID.randomUUID(), UUID.randomUUID(), 0, callbacks, checkpoints).block();

        assertNotNull(result);
        // agent1 已完成，只执行 agent2
        verify(callbacks, times(1)).callReactAgent(anyString(), anyString(), any());
        assertEquals("agent2",
                result.checkpoints().stream()
                        .filter(c -> "COMPLETED".equals(c.status()))
                        .reduce((a, b) -> b).orElseThrow().agentName());
    }
}
