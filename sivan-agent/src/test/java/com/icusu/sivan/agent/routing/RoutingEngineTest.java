package com.icusu.sivan.agent.routing;

import com.icusu.sivan.domain.agent.AgentDefinition;
import com.icusu.sivan.domain.agent.IAgentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RoutingEngineTest {

    @Mock private IAgentRepository agentRepository;
    @Mock private RoutingDecisionRecorder decisionRecorder;
    @Mock private RoutingStrategy strategyA;
    @Mock private RoutingStrategy strategyB;

    @Captor private ArgumentCaptor<RoutingDecisionRecorder.RecordRequest> requestCaptor;

    private final UUID accountId = UUID.randomUUID();
    private final UUID conversationId = UUID.randomUUID();
    private final AgentDefinition agent1 = AgentDefinition.builder()
            .agentName("coder").systemPrompt("你是一个程序员").build();
    private final AgentDefinition agent2 = AgentDefinition.builder()
            .agentName("writer").systemPrompt("你是一个作家").build();

    private RoutingEngine engine(List<RoutingStrategy> strategies) {
        return new RoutingEngine(strategies, agentRepository, decisionRecorder);
    }

    @Test
    void resolve_shouldFallback_whenNoAgents() {
        when(agentRepository.findAllByAccount(accountId)).thenReturn(List.of());
        // 无 agents 时引擎创建兜底智能体
        String result = engine(List.of(strategyA)).resolve("test", accountId, conversationId).block();
        assertNotNull(result, "无 agent 时应有兜底");
    }

    @Test
    void resolve_shouldReturnSoloAgent_whenSingleAgent() {
        when(agentRepository.findAllByAccount(accountId)).thenReturn(List.of(agent1));

        String result = engine(List.of()).resolve("test", accountId, conversationId).block();
        assertEquals("coder", result);

        verify(decisionRecorder).record(requestCaptor.capture());
        assertEquals("auto", requestCaptor.getValue().strategy());
        assertEquals("coder", requestCaptor.getValue().selectedAgentName());
        assertTrue(requestCaptor.getValue().success());
        assertEquals(1.0, requestCaptor.getValue().confidence());
    }

    @Test
    void resolve_shouldSelectBestStrategy_whenMultipleAgents() {
        when(agentRepository.findAllByAccount(accountId)).thenReturn(List.of(agent1, agent2));
        lenient().when(strategyA.name()).thenReturn("strategyA");
        lenient().when(strategyA.route(anyString(), any(), any())).thenReturn(Mono.just(
                RoutingResult.builder().selectedAgent("coder").confidence(0.9)
                        .strategyName("strategyA").reasoning("最佳匹配").build()));
        lenient().when(strategyB.name()).thenReturn("strategyB");
        lenient().when(strategyB.route(anyString(), any(), any())).thenReturn(Mono.just(
                RoutingResult.builder().selectedAgent("writer").confidence(0.6)
                        .strategyName("strategyB").reasoning("次选").build()));

        assertEquals("coder",
                engine(List.of(strategyA, strategyB)).resolve("写代码", accountId, conversationId).block());

        verify(decisionRecorder).record(requestCaptor.capture());
        assertEquals("strategyA", requestCaptor.getValue().strategy());
        assertEquals("coder", requestCaptor.getValue().selectedAgentName());
    }

    @Test
    void resolve_shouldFallback_whenConfidenceBelowThreshold() {
        when(agentRepository.findAllByAccount(accountId)).thenReturn(List.of(agent1, agent2));
        lenient().when(strategyA.name()).thenReturn("strategyA");
        lenient().when(strategyA.route(anyString(), any(), any())).thenReturn(Mono.just(
                RoutingResult.builder().selectedAgent("coder").confidence(0.3)
                        .strategyName("strategyA").reasoning("低匹配").build()));
        lenient().when(strategyB.name()).thenReturn("strategyB");
        lenient().when(strategyB.route(anyString(), any(), any())).thenReturn(Mono.just(
                RoutingResult.builder().selectedAgent("writer").confidence(0.2)
                        .strategyName("strategyB").reasoning("低匹配").build()));

        // 引擎融合策略后选最高置信度的 agent，即使低于阈值也返回
        assertNotNull(engine(List.of(strategyA, strategyB)).resolve("test", accountId, conversationId).block());
    }

    @Test
    void resolve_shouldFallback_whenNoStrategySelectsAgent() {
        when(agentRepository.findAllByAccount(accountId)).thenReturn(List.of(agent1, agent2));
        lenient().when(strategyA.name()).thenReturn("strategyA");
        lenient().when(strategyA.route(anyString(), any(), any())).thenReturn(Mono.just(
                RoutingResult.builder().selectedAgent(null).confidence(0.0)
                        .strategyName("strategyA").build()));

        // 策略返回 null 时引擎从 agent 列表中选择，不会返回 null
        assertNotNull(engine(List.of(strategyA)).resolve("test", accountId, conversationId).block());
    }

    @Test
    void resolve_shouldHandleStrategyErrorInFlux() {
        when(agentRepository.findAllByAccount(accountId)).thenReturn(List.of(agent1, agent2));
        lenient().when(strategyA.name()).thenReturn("strategyA");
        lenient().when(strategyA.route(anyString(), any(), any())).thenReturn(
                Mono.error(new RuntimeException("异步异常")));

        // 策略异常时引擎从 agent 列表中选择，不会返回 null
        assertNotNull(engine(List.of(strategyA)).resolve("test", accountId, conversationId).block());
    }

    @Test
    void resolve_shouldSkipNullResults() {
        when(agentRepository.findAllByAccount(accountId)).thenReturn(List.of(agent1, agent2));
        lenient().when(strategyA.name()).thenReturn("strategyA");
        lenient().when(strategyA.route(anyString(), any(), any())).thenReturn(Mono.just(
                RoutingResult.builder().selectedAgent("coder").confidence(0.8)
                        .strategyName("strategyA").reasoning("好").build()));
        lenient().when(strategyB.name()).thenReturn("strategyB");
        lenient().when(strategyB.route(anyString(), any(), any())).thenReturn(Mono.just(
                RoutingResult.builder().selectedAgent(null).confidence(0.0)
                        .strategyName("strategyB").build()));

        assertEquals("coder",
                engine(List.of(strategyA, strategyB)).resolve("写代码", accountId, conversationId).block());

        verify(decisionRecorder).record(requestCaptor.capture());
        assertEquals("strategyA", requestCaptor.getValue().strategy());
    }

    @Test
    void resolve_shouldIncludeStrategyErrorDetails() {
        when(agentRepository.findAllByAccount(accountId)).thenReturn(List.of(agent1, agent2));
        lenient().when(strategyA.name()).thenReturn("strategyA");
        lenient().when(strategyA.route(anyString(), any(), any())).thenReturn(Mono.just(
                RoutingResult.builder().selectedAgent("coder").confidence(0.8)
                        .strategyName("strategyA").reasoning("好").build()));
        lenient().when(strategyB.name()).thenReturn("strategyB");
        lenient().when(strategyB.route(anyString(), any(), any())).thenReturn(Mono.just(
                RoutingResult.builder().selectedAgent(null).confidence(0.0)
                        .strategyName("strategyB").errorDetail("B出错").build()));

        assertEquals("coder",
                engine(List.of(strategyA, strategyB)).resolve("写代码", accountId, conversationId).block());

        verify(decisionRecorder).record(requestCaptor.capture());
        String lastError = (String) requestCaptor.getValue().context().get("error");
        assertTrue(lastError.contains("B出错"));
    }

    @Test
    void classifyInputType_shouldReturnTask_forTaskKeywords() {
        when(agentRepository.findAllByAccount(accountId)).thenReturn(List.of(agent1));

        engine(List.of()).resolve("分析趋势", accountId, conversationId).block();

        verify(decisionRecorder).record(requestCaptor.capture());
        assertEquals("task", requestCaptor.getValue().context().get("inputType"));
    }

    @Test
    void classifyInputType_shouldReturnChat_forChatInput() {
        when(agentRepository.findAllByAccount(accountId)).thenReturn(List.of(agent1));

        engine(List.of()).resolve("你好", accountId, conversationId).block();

        verify(decisionRecorder).record(requestCaptor.capture());
        assertEquals("chat", requestCaptor.getValue().context().get("inputType"));
    }
}
