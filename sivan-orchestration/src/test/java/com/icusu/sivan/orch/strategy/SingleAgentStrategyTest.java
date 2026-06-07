package com.icusu.sivan.orch.strategy;

import com.icusu.sivan.orch.executor.OrchestrationEvent;
import com.icusu.sivan.agent.model.ModelRouter;
import com.icusu.sivan.agent.routing.RoutingDecisionRecorder;
import com.icusu.sivan.agent.routing.RoutingEngine;
import com.icusu.sivan.agent.service.TokenUsageRecorder;
import com.icusu.sivan.agent.tool.ToolEnricher;
import com.icusu.sivan.agent.tool.ToolResolver;
import com.icusu.sivan.common.context.Account;
import com.icusu.sivan.common.enums.Intent;
import com.icusu.sivan.core.agent.Agent;
import com.icusu.sivan.core.agent.AgentEvent;
import com.icusu.sivan.core.agent.AgentResult;
import com.icusu.sivan.core.agent.ExecutionStrategy;
import com.icusu.sivan.core.message.Msg;
import com.icusu.sivan.core.message.Role;
import com.icusu.sivan.core.model.Model;
import com.icusu.sivan.core.model.TokenUsage;
import com.icusu.sivan.core.tool.ToolProvider;
import com.icusu.sivan.domain.agent.AgentDefinition;
import com.icusu.sivan.domain.agent.IAgentRepository;
import com.icusu.sivan.domain.agent.ISkillRepository;
import com.icusu.sivan.orch.topology.AgentAutoCreator;
import com.icusu.sivan.orch.topology.SkillAutoCreator;
import com.icusu.sivan.orch.topology.TopologyGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SingleAgentStrategyTest {

    @Mock private IAgentRepository agentRepository;
    @Mock private RoutingDecisionRecorder routingDecisionRecorder;
    @Mock private TopologyGenerator topologyGenerator;
    @Mock private AgentAutoCreator agentAutoCreator;
    @Mock private SkillAutoCreator skillAutoCreator;
    @Mock private ToolResolver toolAutoResolver;
    @Mock private ExecutionStrategy executionStrategy;
    @Mock private ModelRouter modelRouter;
    @Mock private ToolProvider toolProvider;
    @Mock private RoutingEngine routingEngine;
    @Mock private ToolEnricher toolEnricher;
    @Mock private TokenUsageRecorder tokenUsageRecorder;
    @Mock private ISkillRepository skillRepository;
    @Mock private Model model;

    private SingleAgentStrategy strategy;

    private final UUID accountId = UUID.randomUUID();
    private final UUID conversationId = UUID.randomUUID();
    private final Account account = new Account(accountId, UUID.randomUUID());

    @BeforeEach
    void setUp() {
        strategy = new SingleAgentStrategy(agentRepository, routingDecisionRecorder,
                topologyGenerator, agentAutoCreator, skillAutoCreator, toolAutoResolver,
                executionStrategy, modelRouter, toolProvider, routingEngine, toolEnricher,
                tokenUsageRecorder, skillRepository);
        when(model.modelId()).thenReturn("test-model");
        when(modelRouter.getDefaultModel(any(UUID.class))).thenReturn(model);
    }

    private OrchestrationStrategy.OrchestrationContext ctx(String task, String targetAgent) {
        return new OrchestrationStrategy.OrchestrationContext(
                task, accountId, null, conversationId, account, targetAgent,
                null, null, null, true, null, "/tmp", false);
    }

    @Test
    void supportedIntent_返回SINGLE_AGENT() {
        assertEquals(Intent.SINGLE_AGENT, strategy.supportedIntent());
    }

    @Test
    void execute_显式指定Agent存在_then执行() {
        AgentDefinition agentDef = AgentDefinition.builder()
                .agentName("coder").systemPrompt("你是一个程序员").build();
        when(agentRepository.findByAccountAndName(accountId, "coder"))
                .thenReturn(Optional.of(agentDef));
        when(toolAutoResolver.resolveForAgent(anyString(), any())).thenReturn(
                new com.icusu.sivan.agent.tool.MatchedTools(List.of(), List.of()));
        when(executionStrategy.execute(any(Agent.class), any()))
                .thenReturn(Flux.just(completedEvent("ok")));

        List<OrchestrationEvent> events = strategy.execute(ctx("写代码", "coder"))
                .collectList().block();
        assertNotNull(events);
        assertTrue(events.stream().anyMatch(e -> e.getType().equals("step_start")));
        assertTrue(events.stream().anyMatch(e -> e.getType().equals("final")));
    }

    @Test
    void execute_路由匹配到Agent_then执行() {
        AgentDefinition agentDef = AgentDefinition.builder()
                .agentName("router-agent").systemPrompt("路由匹配").build();
        when(agentRepository.findByAccountAndName(any(), eq("router-agent")))
                .thenReturn(Optional.of(agentDef));
        when(routingEngine.resolve(anyString(), any(), any())).thenReturn(Mono.just("router-agent"));
        when(toolAutoResolver.resolveForAgent(anyString(), any())).thenReturn(
                new com.icusu.sivan.agent.tool.MatchedTools(List.of(), List.of()));
        when(executionStrategy.execute(any(Agent.class), any()))
                .thenReturn(Flux.just(completedEvent("路由执行成功")));

        List<OrchestrationEvent> events = strategy.execute(ctx("路由请求", null))
                .collectList().block();
        assertNotNull(events);
        assertTrue(events.stream().anyMatch(e -> e.getType().equals("final")));
        verify(routingEngine).resolve(anyString(), any(), any());
    }

    @Test
    void execute_路由未匹配_then自动创建() {
        when(routingEngine.resolve(anyString(), any(), any())).thenReturn(Mono.empty());
        when(topologyGenerator.inferSquadMeta(any(), anyString(), any())).thenReturn(Mono.just(
                new TopologyGenerator.SquadMeta("新Agent", "task", "task")));
        AgentDefinition newAgent = AgentDefinition.builder()
                .agentName("新Agent").systemPrompt("自动创建").build();
        when(agentAutoCreator.create(anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(Mono.just(newAgent));
        when(skillAutoCreator.createForAgent(any(), any(), any())).thenReturn(Mono.empty());
        when(toolAutoResolver.resolveForAgent(anyString(), any())).thenReturn(
                new com.icusu.sivan.agent.tool.MatchedTools(List.of(), List.of()));
        when(executionStrategy.execute(any(Agent.class), any()))
                .thenReturn(Flux.just(completedEvent("ok")));
        when(agentRepository.findByAccountAndName(any(), eq("新Agent")))
                .thenReturn(Optional.of(newAgent));

        List<OrchestrationEvent> events = strategy.execute(ctx("新需求", null))
                .collectList().block();
        assertNotNull(events);
        assertTrue(events.stream().anyMatch(e -> e.getType().equals("final")));
        verify(topologyGenerator).inferSquadMeta(any(), anyString(), any());
    }

    @Test
    void execute_执行异常_then降级() {
        when(routingEngine.resolve(anyString(), any(), any())).thenReturn(Mono.just("crash-agent"));
        when(agentRepository.findByAccountAndName(any(), eq("crash-agent")))
                .thenReturn(Optional.empty()); // agent 不存在
        when(topologyGenerator.inferSquadMeta(any(), anyString(), any())).thenReturn(Mono.just(
                new TopologyGenerator.SquadMeta("fallback", "task", "task")));
        AgentDefinition newAgent = AgentDefinition.builder()
                .agentName("fallback").systemPrompt("降级").build();
        when(agentAutoCreator.create(anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(Mono.just(newAgent));
        when(skillAutoCreator.createForAgent(any(), any(), any())).thenReturn(Mono.empty());
        when(toolAutoResolver.resolveForAgent(anyString(), any())).thenReturn(
                new com.icusu.sivan.agent.tool.MatchedTools(List.of(), List.of()));
        when(executionStrategy.execute(any(Agent.class), any()))
                .thenReturn(Flux.just(completedEvent("ok")));

        List<OrchestrationEvent> events = strategy.execute(ctx("crash", null))
                .collectList().block();
        assertNotNull(events);
    }

    @Test
    void execute_外部异常_onErrorResume() {
        when(routingEngine.resolve(anyString(), any(), any()))
                .thenReturn(Mono.error(new RuntimeException("路由引擎崩溃")));

        List<OrchestrationEvent> events = strategy.execute(ctx("boom", null))
                .collectList().block();
        assertNotNull(events);
        assertTrue(events.stream().anyMatch(e -> e.getType().equals("error")));
    }

    // ── 辅助 ──

    private static AgentEvent completedEvent(String content) {
        return new AgentEvent.Completed(AgentResult.success("test-agent",
                List.of(Msg.of(Role.ASSISTANT, content)), TokenUsage.EMPTY));
    }
}
