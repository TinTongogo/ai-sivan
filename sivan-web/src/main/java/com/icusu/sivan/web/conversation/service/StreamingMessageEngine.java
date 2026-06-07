package com.icusu.sivan.web.conversation.service;

import com.icusu.sivan.common.util.JsonUtil;
import com.icusu.sivan.agent.model.ModelRouter;
import com.icusu.sivan.agent.service.TokenUsageRecorder;
import com.icusu.sivan.agent.strategy.ReActExecutionStrategy;
import com.icusu.sivan.common.enums.MessageStatus;
import com.icusu.sivan.common.exception.DomainException;
import com.icusu.sivan.core.agent.Agent;
import com.icusu.sivan.core.agent.AgentEvent;
import com.icusu.sivan.core.agent.AgentResult;
import com.icusu.sivan.core.agent.ExecutionStrategy;
import com.icusu.sivan.core.context.ExecutionContext;
import com.icusu.sivan.core.message.Msg;
import com.icusu.sivan.core.model.Model;
import com.icusu.sivan.core.tool.ToolProvider;
import com.icusu.sivan.core.tool.ToolSpec;
import com.icusu.sivan.domain.model.LlmProvider;
import com.icusu.sivan.domain.conversation.Conversation;
import com.icusu.sivan.domain.conversation.IConversationRepository;
import com.icusu.sivan.domain.conversation.IMessageRepository;
import com.icusu.sivan.domain.conversation.Message;
import com.icusu.sivan.domain.shared.event.MessageCompletedEvent;
import com.icusu.sivan.domain.shared.vo.TokenContext;
import com.icusu.sivan.infra.shared.sse.SseFormatter;
import com.icusu.sivan.infra.shared.sse.StreamManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 流式消息引擎。管理 LLM 后台流生命周期：Agent 执行 → SSE 推送 → 累积 → 持久化。
 * 内部使用 {@link Agent} 中心入口（LanguageModel + ToolProvider + ExecutionStrategy），
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StreamingMessageEngine {

    private final ModelRouter modelRouter;
    private final ToolProvider toolProvider;
    private final ExecutionStrategy executionStrategy;
    private final StreamManager streamManager;
    private final IMessageRepository messageRepository;
    private final IConversationRepository conversationRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final TokenUsageRecorder tokenUsageRecorder;

    private static final long FLUSH_INTERVAL_MS = 2000;

    private final Map<UUID, Disposable> activeSubscriptions = new ConcurrentHashMap<>();
    private final Map<UUID, AtomicBoolean> cancelledFlags = new ConcurrentHashMap<>();

    /**
     * 在后台启动 Agent 执行流（含工具调用循环），不受 SSE 订阅生命周期影响。
     *
     * @param msgs         核心 Message 列表
     * @param tools        ToolSpec 列表
     * @param providerId   模型提供商 ID（仅用于日志/展示）
     * @param ctx          Token 上下文
     * @param assistantMsg 助理消息实体（RUNNING 占位）
     * @param conversation 对话实体
     */
    @SuppressWarnings({"deprecation", "removal"}) // 在 boundedElastic 线程上调用，accountId 已通过参数传递
    public void start(List<Msg> msgs, List<ToolSpec> tools, UUID providerId, TokenContext ctx, Message assistantMsg, Conversation conversation, boolean stream) {
        StreamAccumulator acc = new StreamAccumulator(assistantMsg);
        UUID msgId = assistantMsg.getMessageId();
        long streamStartMs = System.currentTimeMillis();

        Sinks.Many<String> sink = streamManager.create(msgId);
        sink.tryEmitNext("{\"type\":\"meta\",\"messageId\":\"" + msgId.toString() + "\"}");
        AtomicBoolean cancelled = new AtomicBoolean(false);
        cancelledFlags.put(msgId, cancelled);

        Agent agent = Agent.builder().agentId("chat").languageModel(modelRouter.getDefaultModel(conversation.getAccountId())).toolProvider(toolProvider).executionStrategy(executionStrategy).build();

        Model.ModelParams params = Model.ModelParams.defaults();
        try {
            LlmProvider provider = modelRouter.getDefaultProvider(conversation.getAccountId());
            if (provider.getTemperature() != null) params = params.withTemperature(provider.getTemperature());
            // contextLength 仅用于预算管理，不映射到 max_tokens
            if (provider.getContextLength() != null) {
                params = params.withContextLength(provider.getContextLength());
            }
        } catch (Exception e) {
            log.debug("使用默认 ModelParams: {}", e.getMessage());
        }

        ExecutionContext execCtx = ExecutionContext.create(conversation.getConversationId().toString(), msgs, Map.of(
                ReActExecutionStrategy.ATTR_TOOLS, tools != null ? tools : List.of(),
                ReActExecutionStrategy.ATTR_STREAM, stream,
                ReActExecutionStrategy.ATTR_PARAMS, params));

        Disposable disposable = agent.execute(execCtx).flatMapSequential(event -> handleEvent(event, acc)).doOnNext(sseEvent -> {
            try {
                sink.emitNext(sseEvent, Sinks.EmitFailureHandler.busyLooping(Duration.ofMillis(100)));
            } catch (Exception e) {
                log.warn("SSE event 推送失败: msgId={}", msgId, e.getMessage());
            }
            periodicFlush(acc);
        }).doOnError(err -> {
            String errMsg = err instanceof DomainException ? err.getMessage() : "LLM 调用失败：" + err.getMessage();
            acc.content.append(errMsg);
            sink.tryEmitNext("{\"type\":\"response\",\"content\":\"" + escapeJson(errMsg) + "\"}");
        }).doFinally(signalType -> finalizeStream(signalType, cancelled, acc, assistantMsg, streamStartMs, sink, msgId, conversation)).subscribe(__ -> {
            // data 已在 doOnNext → sink.emitNext 中处理
        }, err -> log.error("LLM 后台流异常: msgId={}", msgId, err), () -> log.debug("LLM 后台流正常完成: msgId={}", msgId));
        activeSubscriptions.put(msgId, disposable);
    }

    /**
     * 取消指定消息的后台流。
     */
    public void cancel(UUID messageId) {
        AtomicBoolean flag = cancelledFlags.get(messageId);
        if (flag != null) flag.set(true);

        Disposable sub = activeSubscriptions.remove(messageId);
        if (sub != null && !sub.isDisposed()) sub.dispose();

        streamManager.complete(messageId);
        streamManager.remove(messageId);

        messageRepository.findById(messageId).ifPresent(msg -> {
            if (msg.getStatus() == null || msg.getStatus() == MessageStatus.RUNNING) {
                msg.setStatus(MessageStatus.FAILED);
                if (msg.getContent() == null || msg.getContent().isEmpty()) {
                    msg.setContent("已取消");
                }
                messageRepository.save(msg);
            }
        });

        cancelledFlags.remove(messageId);
        log.info("LLM 流已取消: msgId={}", messageId);
    }

    /**
     * 注册外部订阅（供编排流等非标准路径使用）。
     */
    public void register(UUID messageId, Disposable disposable) {
        activeSubscriptions.put(messageId, disposable);
    }

    /**
     * 取消注册外部订阅。
     */
    public void unregister(UUID messageId) {
        activeSubscriptions.remove(messageId);
    }

    /**
     * 检查指定消息的流是否活跃。
     */
    public boolean isActive(UUID messageId) {
        return activeSubscriptions.containsKey(messageId);
    }

    // ====== Agent 事件处理 ======

    /**
     * 将 {@link AgentEvent} 映射为 SSE 事件字符串。
     * Chunk/Thinking 分别映射为 response/thinking 事件；
     * ToolCall/ToolResult 仅用于累积状态，不发射 SSE；
     * Completed 记录 token 用量。
     */
    @SuppressWarnings({"deprecation", "removal"}) // ReActExecutionStrategy 在 boundedElastic 线程上执行
    private Flux<String> handleEvent(AgentEvent event, StreamAccumulator acc) {
        return switch (event) {
            case AgentEvent.Chunk c -> {
                acc.content.append(c.delta());
                yield Flux.just(SseFormatter.toJsonEvent("response", c.delta()));
            }
            case AgentEvent.Thinking t -> {
                acc.thinking.append(t.content());
                yield Flux.just(SseFormatter.toJsonEvent("thinking", t.content()));
            }
            case AgentEvent.ToolCall ignored -> Flux.empty();
            case AgentEvent.ToolResult ignored -> Flux.empty();
            case AgentEvent.Completed c -> {
                AgentResult result = c.result();
                if (result.usage() != null && result.usage().totalTokens() > 0) {
                    tokenUsageRecorder.saveUsage(result.usage(), buildRecordingContext(acc), modelRouter.getDefaultModel(acc.message.getAccountId()).modelId());
                }
                acc.model = modelRouter.getDefaultModel(acc.message.getAccountId()).modelId();
                if (result.usage() != null) {
                    acc.totalTokens = result.usage().totalTokens();
                    acc.thinkingTokens = result.usage().thinkingTokens();
                }
                yield Flux.empty();
            }
            case AgentEvent.Error e ->
                    Flux.just("{\"type\":\"response\",\"content\":\"执行异常: " + (e.cause() != null ? escapeJson(e.cause().getMessage()) : "未知错误") + "\"}");
        };
    }

    /**
     * 构造 TokenUsageRecorder 所需的上下文（从 StreamAccumulator/Message 推断）。
     */
    private TokenContext buildRecordingContext(StreamAccumulator acc) {
        return TokenContext.builder().accountId(acc.message.getAccountId()).projectId(acc.message.getProjectId()).conversationId(acc.message.getConversationId()).build();
    }

    // ====== 流终结处理 ======

    private void finalizeStream(SignalType signalType, AtomicBoolean cancelled, StreamAccumulator acc, Message assistantMsg, long streamStartMs, Sinks.Many<String> sink, UUID msgId, Conversation conversation) {
        try {
            if (cancelled.get()) {
                saveTerminalMessage(acc, assistantMsg, MessageStatus.CANCELLED, streamStartMs);
                return;
            }

            int durationMs = (int) (System.currentTimeMillis() - streamStartMs);
            MessageStatus status = signalType == SignalType.ON_COMPLETE ? MessageStatus.COMPLETED : MessageStatus.FAILED;
            saveTerminalMessage(acc, assistantMsg, status, durationMs, streamStartMs);

            try {
                conversation.setMessageCount(conversation.getMessageCount() + 1);
                conversation.setLastMessageAt(LocalDateTime.now(ZoneOffset.UTC));
                conversationRepository.update(conversation);
            } catch (Exception e) {
                log.error("更新对话失败: conversationId={}", conversation.getConversationId(), e);
            }

            if (signalType == SignalType.ON_COMPLETE) {
                int thinkingMs = acc.thinkingDurationMs != null ? acc.thinkingDurationMs : 0;
                String msgIdStr = assistantMsg.getMessageId() != null ? assistantMsg.getMessageId().toString() : null;
                String chainStr = assistantMsg.getChain();

                String meta = SseFormatter.buildMetaEvent(acc.model, acc.totalTokens, durationMs, thinkingMs, acc.thinkingTokens, msgIdStr, chainStr);
                sink.tryEmitNext(meta);
            }

            if (assistantMsg.getGenerationGroup() != null) {
                int total = messageRepository.countByGenerationGroup(assistantMsg.getGenerationGroup());
                sink.tryEmitNext("{\"type\":\"meta\",\"messageId\":\"" + assistantMsg.getMessageId() + "\",\"generationGroup\":\"" + assistantMsg.getGenerationGroup() + "\",\"generationTotal\":" + total + "}");
            }

            streamManager.complete(msgId);
            log.debug("LLM 后台流结束: msgId={}, status={}", msgId, assistantMsg.getStatus());

            if (signalType == SignalType.ON_COMPLETE) {
                eventPublisher.publishEvent(new MessageCompletedEvent(assistantMsg.getMessageId(), conversation.getConversationId(), conversation.getAccountId(), conversation.getProjectId(), assistantMsg.getContent(), assistantMsg.getThinking(), assistantMsg.getModel(), assistantMsg.getTotalTokens() != null ? assistantMsg.getTotalTokens() : 0, assistantMsg.getDurationMs() != null ? assistantMsg.getDurationMs() : 0, assistantMsg.getThinkingDurationMs() != null ? assistantMsg.getThinkingDurationMs() : 0, assistantMsg.getThinkingTokens() != null ? assistantMsg.getThinkingTokens() : 0));
            }
        } finally {
            activeSubscriptions.remove(msgId);
            cancelledFlags.remove(msgId);
        }
    }

    /**
     * 保存流终结时的消息状态。
     * CANCELLED 简写：只设置基本信息，不发射 completion 事件。
     */
    private void saveTerminalMessage(StreamAccumulator acc, Message assistantMsg, MessageStatus status, long streamStartMs) {
        assistantMsg.setContent(acc.content.toString());
        assistantMsg.setThinking(!acc.thinking.isEmpty() ? acc.thinking.toString() : null);
        assistantMsg.setStatus(status);
        if (acc.model != null) assistantMsg.setModel(acc.model);
        if (acc.totalTokens != null) assistantMsg.setTotalTokens(acc.totalTokens);
        assistantMsg.setDurationMs((int) (System.currentTimeMillis() - streamStartMs));
        if (acc.thinkingDurationMs != null) assistantMsg.setThinkingDurationMs(acc.thinkingDurationMs);
        if (acc.thinkingTokens != null) assistantMsg.setThinkingTokens(acc.thinkingTokens);
        saveSafely(assistantMsg, msgId -> log.error("保存消息失败: msgId={}", msgId));
    }

    private void saveTerminalMessage(StreamAccumulator acc, Message assistantMsg, MessageStatus status, int durationMs, long streamStartMs) {
        assistantMsg.setContent(acc.content.toString());
        assistantMsg.setThinking(!acc.thinking.isEmpty() ? acc.thinking.toString() : null);
        assistantMsg.setStatus(status);
        if (acc.model != null) assistantMsg.setModel(acc.model);
        if (acc.totalTokens != null) assistantMsg.setTotalTokens(acc.totalTokens);
        assistantMsg.setDurationMs(durationMs);
        if (acc.thinkingDurationMs != null) assistantMsg.setThinkingDurationMs(acc.thinkingDurationMs);
        if (acc.thinkingTokens != null) assistantMsg.setThinkingTokens(acc.thinkingTokens);
        saveSafely(assistantMsg, msgId -> log.error("保存消息失败: msgId={}", msgId));
    }

    private void saveSafely(Message msg, java.util.function.Consumer<UUID> onError) {
        try {
            messageRepository.save(msg);
        } catch (Exception e) {
            onError.accept(msg.getMessageId());
        }
    }

    // ====== 辅助方法 ======

    private void periodicFlush(StreamAccumulator acc) {
        if (acc.message.getStatus() == MessageStatus.COMPLETED || acc.message.getStatus() == MessageStatus.FAILED)
            return;
        long now = System.currentTimeMillis();
        if (now - acc.lastFlushTime < FLUSH_INTERVAL_MS) return;
        acc.lastFlushTime = now;
        acc.message.setContent(acc.content.toString());
        if (!acc.thinking.isEmpty()) {
            acc.message.setThinking(acc.thinking.toString());
        }
        try {
            messageRepository.save(acc.message);
        } catch (Exception e) {
            log.warn("周期保存消息失败(不影响流): msgId={}", acc.message.getMessageId(), e);
        }
    }

    private static String escapeJson(String s) {
        return JsonUtil.escapeJson(s);
    }

    /**
     * 单流累积器。
     */
    private static class StreamAccumulator {
        final StringBuilder content = new StringBuilder();
        final StringBuilder thinking = new StringBuilder();
        final Message message;
        long lastFlushTime = System.currentTimeMillis();
        String model;
        Integer totalTokens;
        Integer thinkingTokens;
        Integer durationMs;
        Integer thinkingDurationMs;

        StreamAccumulator(Message message) {
            this.message = message;
        }
    }
}
