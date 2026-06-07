package com.icusu.sivan.orch.strategy;

import com.icusu.sivan.orch.executor.OrchestrationEvent;
import com.icusu.sivan.agent.model.ModelRouter;
import com.icusu.sivan.agent.service.TokenUsageRecorder;
import com.icusu.sivan.agent.strategy.ReActExecutionStrategy;
import com.icusu.sivan.common.enums.Intent;
import com.icusu.sivan.common.enums.TokenSource;
import com.icusu.sivan.core.agent.Agent;
import com.icusu.sivan.core.agent.AgentEvent;
import com.icusu.sivan.core.agent.ExecutionStrategy;
import com.icusu.sivan.core.context.ExecutionContext;
import com.icusu.sivan.core.message.Msg;
import com.icusu.sivan.core.message.Role;
import com.icusu.sivan.core.model.Model;
import com.icusu.sivan.core.tool.ToolProvider;
import com.icusu.sivan.core.tool.ToolSpec;
import com.icusu.sivan.domain.model.LlmProvider;
import com.icusu.sivan.domain.shared.vo.TokenContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 聊天策略 — 通过 {@link Agent} 中心入口执行，与 SINGLE_AGENT / SQUAD 共享同一事件协议。
 * <p>
 * LLM 调用 + 工具循环由 {@link ExecutionStrategy}（ReActExecutionStrategy）处理。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatFallbackStrategy implements OrchestrationStrategy {

    private final ModelRouter modelRouter;
    private final ToolProvider toolProvider;
    private final ExecutionStrategy executionStrategy;
    private final TokenUsageRecorder tokenUsageRecorder;

    @Override
    public Intent supportedIntent() {
        return Intent.CHAT;
    }

    @Override
    public Flux<OrchestrationEvent> execute(OrchestrationContext ctx) {
        long startMs = System.currentTimeMillis();
        log.info("ChatFallbackStrategy 执行开始: conversationId={}, taskLen={}, hasTools={}, hasPrebuiltMsgs={}",
                ctx.conversationId(), ctx.taskDescription().length(),
                ctx.mcpTools() != null && !ctx.mcpTools().isEmpty(),
                ctx.chatMsgs() != null && !ctx.chatMsgs().isEmpty());

        List<Msg> msgs = buildInitialMessages(ctx);
        List<ToolSpec> mcpTools = ctx.mcpTools();

        Agent agent = Agent.builder()
                .agentId("chat")
                .languageModel(resolveModel(ctx))
                .toolProvider(toolProvider)
                .executionStrategy(executionStrategy)
                .build();

        Model.ModelParams params = Model.ModelParams.defaults();
        try {
            LlmProvider provider = resolveProvider(ctx);
            if (provider.getTemperature() != null) params = params.withTemperature(provider.getTemperature());
            // contextLength 用于预算管理，不映射到 max_tokens（两者概念不同）
            if (provider.getContextLength() != null) {
                params = params.withContextLength(provider.getContextLength());
            }
        } catch (Exception e) {
            log.debug("使用默认 ModelParams: {}", e.getMessage());
        }

        ExecutionContext execCtx = ExecutionContext.create(ctx.conversationId().toString(), msgs, Map.of(
                ReActExecutionStrategy.ATTR_TOOLS, mcpTools != null ? mcpTools : List.of(),
                ReActExecutionStrategy.ATTR_STREAM, ctx.stream(),
                ReActExecutionStrategy.ATTR_PARAMS, params,
                "_fileRootPath", ctx.fileRootPath() != null ? ctx.fileRootPath() : "",
                "_archived", ctx.archived()
        ));

        TokenContext tokenCtx = TokenContext.builder()
                .accountId(ctx.accountId())
                .projectId(ctx.account() != null ? ctx.account().projectId() : null)
                .conversationId(ctx.conversationId())
                .source(TokenSource.CHAT)
                .build();

        AtomicLong thinkingStartMs = new AtomicLong(0);
        AtomicLong thinkingEndMs = new AtomicLong(0);
        return Flux.concat(
                Flux.just(OrchestrationEvent.stepStart("chat", "LLM 调用")),
                agent.execute(execCtx).flatMapSequential(event ->
                        handleAgentEvent(event, ctx, startMs, tokenCtx, thinkingStartMs, thinkingEndMs))
        );
    }

    private List<Msg> buildInitialMessages(OrchestrationContext ctx) {
        if (ctx.chatMsgs() != null && !ctx.chatMsgs().isEmpty()) {
            // 预构建消息的首条已是完整 SYSTEM（含 projectHint），直接复用
            return new ArrayList<>(ctx.chatMsgs());
        }
        String systemPrompt = com.icusu.sivan.agent.prompt.ChatPrompts.CHAT_SYSTEM.content();
        if (ctx.projectHint() != null) {
            systemPrompt += "\n" + ctx.projectHint();
        }
        List<Msg> msgs = new ArrayList<>();
        msgs.add(Msg.of(Role.SYSTEM, systemPrompt));
        if (ctx.historyContext() != null && !ctx.historyContext().isBlank()) {
            msgs.add(Msg.of(Role.USER, "对话历史：\n" + ctx.historyContext()));
            msgs.add(Msg.of(Role.ASSISTANT, "好的，我已了解上下文。"));
        }
        msgs.add(Msg.of(Role.USER, ctx.taskDescription()));
        return msgs;
    }

    private Flux<OrchestrationEvent> handleAgentEvent(AgentEvent event, OrchestrationContext ctx,
                                                      long startMs, TokenContext tokenCtx,
                                                      AtomicLong thinkingStartMs, AtomicLong thinkingEndMs) {
        return switch (event) {
            case AgentEvent.Chunk c -> {
                if (thinkingStartMs.get() > 0 && thinkingEndMs.get() == 0) {
                    thinkingEndMs.compareAndSet(0, System.currentTimeMillis());
                }
                yield Flux.just(OrchestrationEvent.stream(c.delta()));
            }
            case AgentEvent.Thinking t -> {
                if (thinkingStartMs.get() == 0) {
                    thinkingStartMs.compareAndSet(0, System.currentTimeMillis());
                }
                yield Flux.just(OrchestrationEvent.streamThinking(t.content()));
            }
            case AgentEvent.ToolCall tc -> Flux.just(OrchestrationEvent.toolCall(tc.name(), tc.args()));
            case AgentEvent.ToolResult tr -> Flux.just(OrchestrationEvent.toolResult(tr.name(), tr.success(), tr.output()));
            case AgentEvent.Completed c -> {
                var result = c.result();
                if (result.usage() != null && result.usage().totalTokens() > 0) {
                    tokenUsageRecorder.saveUsage(result.usage(), tokenCtx, resolveModel(ctx).modelId());
                }
                yield emitFinal(ctx, startMs,
                        result.usage() != null ? result.usage().totalTokens() : 0,
                        result.usage() != null ? result.usage().thinkingTokens() : 0,
                        result.content(), result.thinking(), thinkingStartMs, thinkingEndMs);
            }
            case AgentEvent.Error e -> Flux.just(OrchestrationEvent.stream(
                    "执行异常: " + (e.cause() != null ? e.cause().getMessage() : "未知错误")));
        };
    }

    private Flux<OrchestrationEvent> emitFinal(OrchestrationContext ctx, long startMs, int totalTokens,
                                               int thinkingTokens, String content, String thinking,
                                               AtomicLong thinkingStartMs, AtomicLong thinkingEndMs) {
        long durationMs = System.currentTimeMillis() - startMs;
        String modelName;
        try {
            modelName = resolveModel(ctx).modelId();
        } catch (Exception e) {
            log.warn("解析模型失败，降级为 unknown: {}", e.getMessage());
            modelName = "unknown";
        }
        long thinkingEnd = thinkingEndMs.get();
        long thinkingStart = thinkingStartMs.get();
        int thinkingDurationMs = thinkingEnd > 0 && thinkingStart > 0
                ? (int) (thinkingEnd - thinkingStart)
                : 0;
        boolean hasContent = content != null && !content.isBlank();
        boolean hasThinking = thinking != null && !thinking.isBlank();
        log.info("CHAT 策略完成: contentLen={}, hasThinking={}, totalTokens={}, thinkingTokens={}, thinkingDurationMs={}, durationMs={}, model={}",
                hasContent ? content.length() : 0, hasThinking, totalTokens, thinkingTokens, thinkingDurationMs, durationMs, modelName);

        Map<String, Object> stepMeta = new HashMap<>();
        stepMeta.put("tokens", totalTokens);
        stepMeta.put("model", modelName);
        Map<String, Object> meta = new HashMap<>();
        meta.put("type", "chat");
        meta.put("content", content);
        meta.put("thinking", thinking);
        meta.put("model", modelName);
        meta.put("tokens", totalTokens);
        meta.put("durationMs", durationMs);
        meta.put("thinkingTokens", thinkingTokens);
        meta.put("thinkingDurationMs", thinkingDurationMs);
        return Flux.concat(
                Flux.just(OrchestrationEvent.stepEnd("chat", "LLM 调用完成", stepMeta)),
                Flux.just(OrchestrationEvent.complete(meta))
        );
    }

    /** 解析模型：优先使用上下文中的 providerId，否则使用账户默认模型。 */
    private Model resolveModel(OrchestrationContext ctx) {
        if (ctx.providerId() != null) {
            return modelRouter.getModel(ctx.providerId());
        }
        return modelRouter.getDefaultModel(ctx.accountId());
    }

    /** 解析 LLM 提供商：优先使用上下文中的 providerId，否则使用账户默认提供商。 */
    private LlmProvider resolveProvider(OrchestrationContext ctx) {
        if (ctx.providerId() != null) {
            return modelRouter.getProvider(ctx.providerId());
        }
        return modelRouter.getDefaultProvider(ctx.accountId());
    }
}
