package com.icusu.sivan.orch.strategy;

import com.icusu.sivan.orch.executor.OrchestrationEvent;
import com.icusu.sivan.agent.model.ModelRouter;
import com.icusu.sivan.agent.service.TokenUsageRecorder;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChatFallbackStrategyTest {

    @Mock private ModelRouter modelRouter;
    @Mock private ToolProvider toolProvider;
    @Mock private ExecutionStrategy executionStrategy;
    @Mock private TokenUsageRecorder tokenUsageRecorder;
    @Mock private Model model;

    private ChatFallbackStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new ChatFallbackStrategy(modelRouter, toolProvider, executionStrategy, tokenUsageRecorder);
        when(model.modelId()).thenReturn("test-model");
        when(modelRouter.getDefaultModel(any(UUID.class))).thenReturn(model);
    }

    @Test
    void supportedIntent_返回CHAT() {
        assertEquals(Intent.CHAT, strategy.supportedIntent());
    }

    @Test
    void execute_使用预构建消息() {
        OrchestrationStrategy.OrchestrationContext ctx = ctxWithMsgs(List.of(Msg.of(Role.SYSTEM, "custom")));
        when(executionStrategy.execute(any(Agent.class), any()))
                .thenReturn(Flux.just(completedEvent("hi")));

        List<OrchestrationEvent> events = strategy.execute(ctx).collectList().block();
        assertNotNull(events);
        assertEquals(3, events.size());
        assertEquals("step_start", events.get(0).getType());
        assertEquals("final", events.get(2).getType());
    }

    @Test
    void execute_使用默认消息构建() {
        OrchestrationStrategy.OrchestrationContext ctx = defaultCtx("task");
        when(executionStrategy.execute(any(Agent.class), any()))
                .thenReturn(Flux.just(completedEvent("done")));

        List<OrchestrationEvent> events = strategy.execute(ctx).collectList().block();
        assertNotNull(events);
        assertEquals(3, events.size());
    }

    @Test
    void execute_包含对话历史() {
        OrchestrationStrategy.OrchestrationContext ctx = new OrchestrationStrategy.OrchestrationContext(
                "task", UUID.randomUUID(), "之前聊过", UUID.randomUUID(),
                null, null, null, null,
                null, true, null, "/tmp", false);
        when(executionStrategy.execute(any(Agent.class), any()))
                .thenReturn(Flux.just(completedEvent("resp")));

        List<OrchestrationEvent> events = strategy.execute(ctx).collectList().block();
        assertEquals(3, events.size());
    }

    @Test
    void execute_包含projectHint() {
        OrchestrationStrategy.OrchestrationContext ctx = new OrchestrationStrategy.OrchestrationContext(
                "task", UUID.randomUUID(), null, UUID.randomUUID(),
                null, null, null, null,
                null, true, "项目提示", "/tmp", false);
        when(executionStrategy.execute(any(Agent.class), any()))
                .thenReturn(Flux.just(completedEvent("ok")));

        List<OrchestrationEvent> events = strategy.execute(ctx).collectList().block();
        assertEquals(3, events.size());
    }

    @Test
    void execute_流式Chunk事件() {
        OrchestrationStrategy.OrchestrationContext ctx = basicCtx();
        when(executionStrategy.execute(any(Agent.class), any()))
                .thenReturn(Flux.just(
                        new AgentEvent.Thinking("思考中"),
                        new AgentEvent.Chunk("字1"),
                        completedEvent("最终")));

        List<OrchestrationEvent> events = strategy.execute(ctx).collectList().block();
        assertNotNull(events);
        assertTrue(events.stream().anyMatch(e -> e.getType().equals("stream_thinking")));
        assertTrue(events.stream().anyMatch(e -> e.getType().equals("stream")));
    }

    @Test
    void execute_ToolCallToolResult事件() {
        OrchestrationStrategy.OrchestrationContext ctx = basicCtx();
        when(executionStrategy.execute(any(Agent.class), any()))
                .thenReturn(Flux.just(
                        new AgentEvent.ToolCall("1", "read", java.util.Map.of()),
                        new AgentEvent.ToolResult("1", "read", true, "content"),
                        completedEvent("done")));

        List<OrchestrationEvent> events = strategy.execute(ctx).collectList().block();
        assertNotNull(events);
        assertTrue(events.stream().anyMatch(e -> e.getType().equals("tool_call")));
        assertTrue(events.stream().anyMatch(e -> e.getType().equals("tool_result")));
    }

    @Test
    void execute_AgentError输出异常信息() {
        OrchestrationStrategy.OrchestrationContext ctx = basicCtx();
        when(executionStrategy.execute(any(Agent.class), any()))
                .thenReturn(Flux.just(new AgentEvent.Error(new RuntimeException("API 超时"))));

        List<OrchestrationEvent> events = strategy.execute(ctx).collectList().block();
        assertNotNull(events);
        assertTrue(events.stream().anyMatch(e -> e.getMessage().contains("异常")));
    }

    @Test
    void execute_使用指定providerId() {
        UUID providerId = UUID.randomUUID();
        OrchestrationStrategy.OrchestrationContext ctx = new OrchestrationStrategy.OrchestrationContext(
                "hello", UUID.randomUUID(), null, UUID.randomUUID(),
                null, null, null, providerId,
                List.of(Msg.of(Role.SYSTEM, "sys")), true, null, "/tmp", false);
        when(modelRouter.getModel(providerId)).thenReturn(model);
        when(executionStrategy.execute(any(Agent.class), any()))
                .thenReturn(Flux.just(completedEvent("ok")));

        strategy.execute(ctx).blockLast();
        verify(modelRouter, atLeastOnce()).getModel(providerId);
    }

    @Test
    void execute_完成时记录token() {
        OrchestrationStrategy.OrchestrationContext ctx = basicCtx();
        when(executionStrategy.execute(any(Agent.class), any()))
                .thenReturn(Flux.just(completedEventWithTokens("done", 150, 30)));

        strategy.execute(ctx).blockLast();
        verify(tokenUsageRecorder).saveUsage(any(TokenUsage.class), any(com.icusu.sivan.domain.shared.vo.TokenContext.class), eq("test-model"));
    }

    @Test
    void execute_无token消耗不记录() {
        OrchestrationStrategy.OrchestrationContext ctx = basicCtx();
        when(executionStrategy.execute(any(Agent.class), any()))
                .thenReturn(Flux.just(completedEvent("no-usage")));

        strategy.execute(ctx).blockLast();
        verify(tokenUsageRecorder, never()).saveUsage(any(TokenUsage.class), any(), any());
    }

    // ── 辅助方法 ──

    private static OrchestrationStrategy.OrchestrationContext basicCtx() {
        return ctxWithMsgs(List.of(Msg.of(Role.SYSTEM, "sys")));
    }

    private static OrchestrationStrategy.OrchestrationContext ctxWithMsgs(List<Msg> msgs) {
        return new OrchestrationStrategy.OrchestrationContext(
                "test", UUID.randomUUID(), null, UUID.randomUUID(),
                null, null, null, null,
                msgs, true, null, "/tmp", false);
    }

    private static OrchestrationStrategy.OrchestrationContext defaultCtx(String task) {
        return new OrchestrationStrategy.OrchestrationContext(
                task, UUID.randomUUID(), null, UUID.randomUUID(),
                null, null, null, null,
                null, true, null, "/tmp", false);
    }

    private static AgentEvent completedEvent(String content) {
        return new AgentEvent.Completed(new AgentResult("chat",
                List.of(Msg.of(Role.ASSISTANT, content)), TokenUsage.EMPTY, true, null));
    }

    private static AgentEvent completedEventWithTokens(String content, int totalTokens, int thinkingTokens) {
        return new AgentEvent.Completed(new AgentResult("chat",
                List.of(Msg.of(Role.ASSISTANT, content)),
                new TokenUsage(0, 0, totalTokens, thinkingTokens), true, null));
    }
}
