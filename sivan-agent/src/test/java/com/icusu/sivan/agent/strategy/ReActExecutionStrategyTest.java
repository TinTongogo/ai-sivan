package com.icusu.sivan.agent.strategy;

import com.icusu.sivan.core.agent.Agent;
import com.icusu.sivan.core.agent.AgentEvent;
import com.icusu.sivan.core.context.ExecutionContext;
import com.icusu.sivan.core.message.Content;
import com.icusu.sivan.core.message.Msg;
import com.icusu.sivan.core.message.Role;
import com.icusu.sivan.core.model.Model;
import com.icusu.sivan.core.model.TokenUsage;
import com.icusu.sivan.core.tool.ToolProvider;
import com.icusu.sivan.core.tool.ToolResult;
import com.icusu.sivan.core.tool.ToolSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReActExecutionStrategyTest {

    @Mock private Model model;
    @Mock private ToolProvider toolProvider;

    private ReActExecutionStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new ReActExecutionStrategy();
    }

    /** 创建 ExecutionContext，预先关闭流式以便使用非流式 chat() 路径测试。 */
    private ExecutionContext ctx(List<Msg> msgs, List<ToolSpec> tools) {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put(ReActExecutionStrategy.ATTR_STREAM, false);
        if (tools != null && !tools.isEmpty()) {
            attrs.put(ReActExecutionStrategy.ATTR_TOOLS, tools);
        }
        return ExecutionContext.create("conv-1", msgs, attrs);
    }

    private Agent agent() {
        return Agent.builder()
                .agentId("test-agent")
                .languageModel(model)
                .toolProvider(toolProvider)
                .executionStrategy(strategy)
                .build();
    }

    /** 从 Flux 收集所有事件。 */
    private List<AgentEvent> collect(Flux<AgentEvent> flux) {
        return flux.collectList().block(Duration.ofSeconds(10));
    }

    @Test
    void noTools_simpleText_completesSuccessfully() {
        var msgs = List.of(Msg.of(Role.USER, "hello"));
        var ctx = ctx(msgs, List.of());
        var agent = agent();

        var replyMsg = Msg.of(Role.ASSISTANT, "Hi there!");
        var response = new Model.ModelResponse(replyMsg, new TokenUsage(10, 20, 30));
        when(model.chat(anyList(), anyList(), any())).thenReturn(Mono.just(response));

        var events = collect(agent.execute(ctx));

        var completed = events.stream()
                .filter(e -> e instanceof AgentEvent.Completed)
                .findFirst().orElseThrow();
        var result = ((AgentEvent.Completed) completed).result();
        assertTrue(result.success());
        assertEquals("test-agent", result.agentId());
    }

    @Test
    void tools_singleRound_completesWithToolCall() {
        var msgs = List.of(Msg.of(Role.USER, "search something"));
        var tools = List.of(new ToolSpec("search", "Search tool", Map.of()));
        var ctx = ctx(msgs, tools);
        var agent = agent();

        // 第一轮：模型返回工具调用
        var toolCallContent = new Content.ToolCall("call-1", "search", Map.of("q", "hello"));
        var toolCallMsg = Msg.of(Role.ASSISTANT, List.of(toolCallContent));
        var toolResp = new Model.ModelResponse(toolCallMsg, new TokenUsage(10, 5, 15));

        // 第二轮：模型返回最终文本
        var finalText = Msg.of(Role.ASSISTANT, "Found results");
        var finalResp = new Model.ModelResponse(finalText, new TokenUsage(15, 10, 25));

        when(model.chat(anyList(), anyList(), any()))
                .thenReturn(Mono.just(toolResp))
                .thenReturn(Mono.just(finalResp));

        when(toolProvider.execute(eq("search"), anyMap()))
                .thenReturn(Mono.just(ToolResult.success("call-1", "result data")));

        var events = collect(agent.execute(ctx));

        var completed = events.stream()
                .filter(e -> e instanceof AgentEvent.Completed)
                .findFirst().orElseThrow();
        var result = ((AgentEvent.Completed) completed).result();
        assertTrue(result.success());

        var toolCalls = events.stream().filter(e -> e instanceof AgentEvent.ToolCall).count();
        var toolResults = events.stream().filter(e -> e instanceof AgentEvent.ToolResult).count();
        assertEquals(1, toolCalls);
        assertEquals(1, toolResults);

        verify(toolProvider).execute(eq("search"), anyMap());
        verify(model, times(2)).chat(anyList(), anyList(), any());
    }

    @Test
    void maxRounds_exceeded_stopsGracefully() {
        var msgs = List.of(Msg.of(Role.USER, "loop"));
        var tools = List.of(new ToolSpec("search", "Search", Map.of()));
        var ctx = ctx(msgs, tools);
        var agent = agent();

        var toolCallContent = new Content.ToolCall("call-1", "search", Map.of("q", "loop"));
        var toolCallMsg = Msg.of(Role.ASSISTANT, List.of(toolCallContent));
        var response = new Model.ModelResponse(toolCallMsg, TokenUsage.EMPTY);

        when(model.chat(anyList(), anyList(), any())).thenReturn(Mono.just(response));
        when(toolProvider.execute(eq("search"), anyMap()))
                .thenReturn(Mono.just(ToolResult.success("call-1", "ok")));

        var events = collect(agent.execute(ctx));

        var completed = events.stream()
                .filter(e -> e instanceof AgentEvent.Completed)
                .findFirst().orElseThrow();
        assertTrue(((AgentEvent.Completed) completed).result().success());
    }

    @Test
    void consecutiveSameTool_exceedsLimit_stops() {
        var msgs = List.of(Msg.of(Role.USER, "search repeatedly"));
        var tools = List.of(new ToolSpec("search", "Search", Map.of()));
        var ctx = ctx(msgs, tools);
        var agent = agent();

        var toolCallContent = new Content.ToolCall("call-id", "search", Map.of("q", "x"));
        var toolCallMsg = Msg.of(Role.ASSISTANT, List.of(toolCallContent));
        var response = new Model.ModelResponse(toolCallMsg, TokenUsage.EMPTY);

        when(model.chat(anyList(), anyList(), any())).thenReturn(Mono.just(response));
        when(toolProvider.execute(eq("search"), anyMap()))
                .thenReturn(Mono.just(ToolResult.success("call-id", "ok")));

        var events = collect(agent.execute(ctx));
        var completed = events.stream()
                .filter(e -> e instanceof AgentEvent.Completed)
                .findFirst().orElseThrow();
        assertTrue(((AgentEvent.Completed) completed).result().success());
    }

    @Test
    void allToolsFail_exceedsMaxFailed_stops() {
        var msgs = List.of(Msg.of(Role.USER, "run failing tool"));
        var tools = List.of(new ToolSpec("failing", "Fails", Map.of()));
        var ctx = ctx(msgs, tools);
        var agent = agent();

        var toolCallContent = new Content.ToolCall("call-1", "failing", Map.of());
        var toolCallMsg = Msg.of(Role.ASSISTANT, List.of(toolCallContent));
        var response = new Model.ModelResponse(toolCallMsg, TokenUsage.EMPTY);

        when(model.chat(anyList(), anyList(), any())).thenReturn(Mono.just(response));
        when(toolProvider.execute(eq("failing"), anyMap()))
                .thenReturn(Mono.just(ToolResult.failure("call-1", "error")));

        var events = collect(agent.execute(ctx));
        var completed = events.stream()
                .filter(e -> e instanceof AgentEvent.Completed)
                .findFirst().orElseThrow();
        assertTrue(((AgentEvent.Completed) completed).result().success());
        // 应在 3 轮失败后停止（MAX_FAILED_ROUNDS=3）
        verify(toolProvider, atMost(6)).execute(eq("failing"), anyMap());
    }

    @Test
    void modelError_withTools_fallsBackToNoTools() {
        var msgs = List.of(Msg.of(Role.USER, "hello"));
        var tools = List.of(new ToolSpec("search", "Search", Map.of()));
        var ctx = ctx(msgs, tools);
        var agent = agent();

        when(model.chat(anyList(), anyList(), any()))
                .thenReturn(Mono.error(new RuntimeException("API error")))
                .thenReturn(Mono.just(new Model.ModelResponse(
                        Msg.of(Role.ASSISTANT, "fallback response"), TokenUsage.EMPTY)));

        var events = collect(agent.execute(ctx));
        var completed = events.stream()
                .filter(e -> e instanceof AgentEvent.Completed)
                .findFirst().orElseThrow();
        var result = ((AgentEvent.Completed) completed).result();
        assertTrue(result.success());

        verify(model, times(2)).chat(anyList(), anyList(), any());
    }

    @Test
    void toolExecutionError_handlesGracefully() {
        var msgs = List.of(Msg.of(Role.USER, "search"));
        var tools = List.of(new ToolSpec("search", "Search", Map.of()));
        var ctx = ctx(msgs, tools);
        var agent = agent();

        var toolCallContent = new Content.ToolCall("call-1", "search", Map.of());
        var toolCallMsg = Msg.of(Role.ASSISTANT, List.of(toolCallContent));
        var response = new Model.ModelResponse(toolCallMsg, TokenUsage.EMPTY);

        var finalText = Msg.of(Role.ASSISTANT, "done");
        var finalResp = new Model.ModelResponse(finalText, TokenUsage.EMPTY);

        when(model.chat(anyList(), anyList(), any()))
                .thenReturn(Mono.just(response))
                .thenReturn(Mono.just(finalResp));

        when(toolProvider.execute(eq("search"), anyMap()))
                .thenReturn(Mono.error(new RuntimeException("tool crashed")));

        var events = collect(agent.execute(ctx));
        var completed = events.stream()
                .filter(e -> e instanceof AgentEvent.Completed)
                .findFirst().orElseThrow();
        assertTrue(((AgentEvent.Completed) completed).result().success());

        var toolResults = events.stream()
                .filter(e -> e instanceof AgentEvent.ToolResult)
                .map(e -> (AgentEvent.ToolResult) e)
                .toList();
        assertFalse(toolResults.isEmpty());
        assertFalse(toolResults.get(0).success());
    }

    @Test
    void concurrentToolCall_allExecuted() {
        var msgs = List.of(Msg.of(Role.USER, "run multiple tools"));
        var tools = List.of(
                new ToolSpec("tool-a", "Tool A", Map.of()),
                new ToolSpec("tool-b", "Tool B", Map.of()));
        var ctx = ctx(msgs, tools);
        var agent = agent();

        var toolCallContent1 = new Content.ToolCall("call-a", "tool-a", Map.of());
        var toolCallContent2 = new Content.ToolCall("call-b", "tool-b", Map.of());
        var toolCallMsg = Msg.of(Role.ASSISTANT, List.of(toolCallContent1, toolCallContent2));
        var toolResp = new Model.ModelResponse(toolCallMsg, TokenUsage.EMPTY);
        var finalMsg = Msg.of(Role.ASSISTANT, "all done");
        var finalResp = new Model.ModelResponse(finalMsg, TokenUsage.EMPTY);

        when(model.chat(anyList(), anyList(), any()))
                .thenReturn(Mono.just(toolResp))
                .thenReturn(Mono.just(finalResp));

        when(toolProvider.execute(eq("tool-a"), anyMap()))
                .thenReturn(Mono.just(ToolResult.success("call-a", "result-a")));
        when(toolProvider.execute(eq("tool-b"), anyMap()))
                .thenReturn(Mono.just(ToolResult.success("call-b", "result-b")));

        var events = collect(agent.execute(ctx));
        var completed = events.stream()
                .filter(e -> e instanceof AgentEvent.Completed)
                .findFirst().orElseThrow();
        assertTrue(((AgentEvent.Completed) completed).result().success());

        verify(toolProvider).execute(eq("tool-a"), anyMap());
        verify(toolProvider).execute(eq("tool-b"), anyMap());

        assertEquals(2, events.stream().filter(e -> e instanceof AgentEvent.ToolCall).count());
        assertEquals(2, events.stream().filter(e -> e instanceof AgentEvent.ToolResult).count());
    }
}
