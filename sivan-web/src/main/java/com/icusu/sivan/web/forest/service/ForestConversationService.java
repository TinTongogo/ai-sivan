package com.icusu.sivan.web.forest.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.icusu.sivan.common.enums.MessageStatus;
import com.icusu.sivan.common.exception.DomainException;
import com.icusu.sivan.common.exception.ResourceNotFoundException;
import com.icusu.sivan.common.util.JsonUtil;
import com.icusu.sivan.core.message.Msg;
import com.icusu.sivan.core.tool.ToolSpec;
import com.icusu.sivan.domain.conversation.Conversation;
import com.icusu.sivan.domain.conversation.IConversationRepository;
import com.icusu.sivan.domain.conversation.IMessageRepository;
import com.icusu.sivan.domain.conversation.Message;
import com.icusu.sivan.domain.forest.Forest;
import com.icusu.sivan.domain.forest.ForestEvent;
import com.icusu.sivan.domain.forest.context.Delivery;
import com.icusu.sivan.domain.forest.context.ExecutionContext;
import com.icusu.sivan.domain.forest.service.ForestRepository;
import com.icusu.sivan.domain.forest.service.TreeMatcher;
import com.icusu.sivan.domain.forest.tree.ExecutableNode;
import com.icusu.sivan.domain.forest.tree.TaskNode;
import com.icusu.sivan.domain.shared.event.MessageCompletedEvent;
import com.icusu.sivan.infra.forest.compression.ForestCompressor;
import com.icusu.sivan.infra.forest.execution.ForestExecutor;
import com.icusu.sivan.infra.shared.sse.SseFormatter;
import com.icusu.sivan.infra.shared.sse.StreamManager;
import com.icusu.sivan.web.conversation.dto.*;
import com.icusu.sivan.web.conversation.service.ConversationCompressionService;
import com.icusu.sivan.web.conversation.service.ConversationCrudService;
import com.icusu.sivan.web.conversation.service.MessageCrudService;
import com.icusu.sivan.web.conversation.service.PromptContextService;
import com.icusu.sivan.web.conversation.service.PromptContextService.ChatToolResult;
import com.icusu.sivan.web.conversation.service.message.MessageAttachmentsSerializer;
import com.icusu.sivan.web.conversation.service.tree.ContextResult;
import com.icusu.sivan.agent.model.ModelRouter;
import com.icusu.sivan.web.service.GroupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Forest 对话服务 — 对话 CRUD、消息收发、流式 LLM 调用与 Forest 编排执行。
 * <p>
 * 统一使用 {@link ForestExecutor} 进行树执行，通过 {@link StreamManager} 实现
 * LLM 后台执行与 SSE 连接的生命周期解耦。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ForestConversationService {

    private final IConversationRepository conversationRepository;
    private final IMessageRepository messageRepository;
    private final StreamManager streamManager;
    private final ConversationCompressionService conversationCompressionService;
    private final TreeMatcher treeMatcher;
    private final ForestExecutor forestExecutor;
    private final ForestCompressor forestCompressor;
    private final ConversationCrudService conversationCrudService;
    private final MessageCrudService messageCrudService;
    private final PromptContextService promptContextService;
    private final ModelRouter modelRouter;
    private final GroupService groupService;
    private final ForestRepository forestRepository;
    private final TransactionTemplate transactionTemplate;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // ============ 对话 CRUD ============

    public ConversationResponse create(UUID accountId, CreateConversationRequest request) {
        return conversationCrudService.create(accountId, request);
    }

    public ConversationResponse getById(UUID accountId, UUID conversationId) {
        return conversationCrudService.getById(accountId, conversationId);
    }

    public List<ConversationResponse> list(UUID accountId, UUID projectId) {
        return conversationCrudService.list(accountId, projectId);
    }

    public ConversationResponse update(UUID accountId, UUID conversationId, UpdateConversationRequest request) {
        return conversationCrudService.update(accountId, conversationId, request);
    }

    public void delete(UUID accountId, UUID conversationId) {
        conversationCrudService.delete(accountId, conversationId);
    }

    public Conversation findOwned(UUID accountId, UUID conversationId) {
        return conversationCrudService.findOwned(accountId, conversationId);
    }

    // ============ 消息 CRUD ============

    @Transactional
    public MessageResponse sendMessage(UUID accountId, UUID conversationId, SendMessageRequest request) {
        return messageCrudService.sendMessage(accountId, conversationId, request);
    }

    public MessagePageResponse getMessages(UUID accountId, UUID conversationId) {
        return messageCrudService.getMessages(accountId, conversationId);
    }

    public MessagePageResponse getMessages(UUID accountId, UUID conversationId, Integer beforeSortOrder, int limit) {
        return messageCrudService.getMessages(accountId, conversationId, beforeSortOrder, limit);
    }

    public List<MessageResponse> getGenerations(UUID accountId, UUID conversationId, UUID messageId) {
        return messageCrudService.getGenerations(accountId, conversationId, messageId);
    }

    public void deleteMessage(UUID accountId, UUID messageId) {
        messageCrudService.deleteMessage(accountId, messageId);
    }

    public int countMessages(UUID accountId, UUID conversationId) {
        return messageCrudService.countMessages(accountId, conversationId);
    }

    public MessageResponse rateMessage(UUID accountId, UUID messageId, String rating) {
        return messageCrudService.rateMessage(accountId, messageId, rating);
    }

    // ============ 流式消息 ============

    /**
     * 发送消息并流式返回 LLM 回复。
     * LLM 调用在后台独立线程运行，与 SSE 订阅解耦。
     * 客户端断开 SSE 不影响 LLM 继续生成，可重新订阅续接。
     */
    public Flux<String> streamMessage(UUID accountId, UUID conversationId, SendMessageRequest request) {
        Conversation conversation = conversationCrudService.findOwned(accountId, conversationId);

        // 先保存用户消息（含图片），确保前端能展示
        Message userMessage = MessageCrudService.buildUserMessage(conversationId, accountId, conversation, request);
        messageRepository.save(userMessage);

        // 保存 MCP 工具选择到对话
        List<String> serverIds = request.getMcpServerIds() != null
                ? request.getMcpServerIds().stream().map(UUID::toString).toList()
                : List.of();
        conversation.setMcpServerIds(serverIds.isEmpty() ? null : serverIds);
        conversationRepository.update(conversation);

        // 上传文件复制到沙盒工作目录，确保 file_read 等工具可访问
        List<String> sandboxWarnings = promptContextService.copyAttachmentsToSandbox(accountId, conversation, request);

        Flux<String> responseStream = Mono.fromCallable(() -> doOrchestrateAndRoute(conversationId, request, conversation, accountId))
                .subscribeOn(Schedulers.boundedElastic()).flatMapMany(flux -> flux);

        if (sandboxWarnings != null && !sandboxWarnings.isEmpty()) {
            String warningJson = "{\"type\":\"error\",\"message\":\"以下文件未能复制到沙盒工作目录，文件工具可能无法访问：" + String.join("、", sandboxWarnings) + "\"}";
            responseStream = Flux.concat(Flux.just(warningJson), responseStream);
        }

        return responseStream;
    }

    private Flux<String> doOrchestrateAndRoute(UUID conversationId, SendMessageRequest request,
                                                Conversation conversation, UUID accountId) {
        OrchPrep prep = prepareOrchestration(conversation, request.getModelProviderId(), accountId);
        return runOrchestration(conversationId, request, conversation, accountId, prep);
    }

    private record OrchPrep(Message assistantMsg, Sinks.Many<String> sink, UUID msgId,
                            int contextLength, int maxPromptTokens, int historyBudget, String modelName) {
    }

    private OrchPrep prepareOrchestration(Conversation conversation, UUID providerId, UUID accountId) {
        int contextLength = promptContextService.resolveContextLength(providerId, accountId);
        int maxPromptTokens = (int) (contextLength * promptContextService.resolveBudgetRatio(providerId, accountId));
        int historyBudget = (int) (maxPromptTokens * 0.85);

        String modelName = resolveModelName(providerId, accountId);
        Message assistantMsg = messageRepository.save(messageCrudService.createAssistantMessage(conversation, accountId));
        conversation.incrementMessageCount();
        conversationRepository.update(conversation);

        UUID msgId = assistantMsg.getMessageId();
        Sinks.Many<String> sink = streamManager.create(msgId);
        sink.tryEmitNext(SseFormatter.buildMetaEvent(null, null, 0, 0, msgId.toString()));

        return new OrchPrep(assistantMsg, sink, msgId, contextLength, maxPromptTokens, historyBudget, modelName);
    }

    private String resolveModelName(UUID providerId, UUID accountId) {
        try {
            if (providerId != null) {
                var provider = modelRouter.getProvider(providerId);
                return provider != null ? provider.getPrimaryModelName() : null;
            }
            var model = modelRouter.getDefaultModel(accountId);
            return model != null ? model.modelId() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 统一执行路径：TreeMatcher 构建树 → ForestExecutor 执行 → SSE 推送 → 持久化。
     */
    private Flux<String> runOrchestration(UUID conversationId, SendMessageRequest request,
                                           Conversation conversation, UUID accountId, OrchPrep prep) {
        log.info("Forest 执行启动: conversationId={}, msgId={}", conversationId, prep.msgId);

        Consumer<String> progress = msg -> log.debug("编排进度: {}", msg);
        List<Message> allMessages = messageRepository.findByConversationId(conversationId);

        conversationCompressionService.resolveCompressResult(conversation, conversationId, prep.historyBudget,
                        accountId, progress, request.getContent(), allMessages)
                .flatMap(compressResult -> {
                    ChatToolResult toolResult = promptContextService.resolveChatTools(conversation, request.getContent(),
                            compressResult.toSummaryText(), accountId);
                    List<ToolSpec> coreTools = toolResult.tools != null
                            ? toolResult.tools.stream()
                            .map(s -> new ToolSpec(s.name(), s.description(), s.inputSchema()))
                            .toList()
                            : null;

                    log.info("Forest 执行: toolCount={}", coreTools != null ? coreTools.size() : 0);

                    List<Msg> prebuiltMsgs = promptContextService.buildLlmMessages(
                            conversationId, accountId, null, null,
                            prep.contextLength, null, null, null, null, null);

                    return treeMatcher.match(request.getContent(), accountId)
                            .switchIfEmpty(Mono.fromRunnable(() -> {
                                prep.assistantMsg.setContent("执行树构建失败");
                                prep.assistantMsg.setStatus(MessageStatus.FAILED);
                                messageRepository.save(prep.assistantMsg);
                                streamManager.complete(prep.msgId);
                            }))
                            .flatMap(tree -> {
                                String projectPath = groupService.getProjectRootPath(accountId, conversation.getProjectId());

                                Forest forest = persistForestTree(tree, request.getContent(),
                                        accountId, conversation.getProjectId());

                                forestCompressor.compress(forest, tree, "send", prep.maxPromptTokens);
                                addRuntimeMetadata(tree, prebuiltMsgs, coreTools, forest.forestId(), projectPath);

                                ExecutionContext execCtx = ExecutionContext.create(accountId,
                                        conversation.getProjectId(), conversationId);

                                StringBuilder contentAcc = new StringBuilder();
                                long startMs = System.currentTimeMillis();

                                final int[] totalTokens = {0};
                                final int[] thinkingTokens = {0};
                                final long[] thinkingStartMs = {0};
                                final long[] thinkingDurationAcc = {0};

                                forestExecutor.execute(tree, execCtx, Delivery.SUMMARY)
                                        .doOnNext(event -> {
                                            switch (event.type()) {
                                                case DETAIL -> {
                                                    if (thinkingStartMs[0] > 0) {
                                                        thinkingDurationAcc[0] += System.currentTimeMillis() - thinkingStartMs[0];
                                                        thinkingStartMs[0] = 0;
                                                    }
                                                    contentAcc.append(event.message());
                                                    prep.sink.tryEmitNext(SseFormatter.toJsonEvent("response", event.message()));
                                                }
                                                case THINKING -> {
                                                    if (thinkingStartMs[0] == 0)
                                                        thinkingStartMs[0] = System.currentTimeMillis();
                                                    prep.sink.tryEmitNext(SseFormatter.toJsonEvent("thinking", event.message()));
                                                }
                                                case MILESTONE -> {
                                                    String m = event.message();
                                                    try {
                                                        totalTokens[0] = Integer.parseInt(
                                                                m.replaceAll(".*\"totalTokens\":(\\d+).*", "$1"));
                                                    } catch (Exception ignored) {
                                                    }
                                                    try {
                                                        thinkingTokens[0] = Integer.parseInt(
                                                                m.replaceAll(".*\"thinkingTokens\":(\\d+).*", "$1"));
                                                    } catch (Exception ignored) {
                                                    }
                                                }
                                                case ERROR -> {
                                                    prep.sink.tryEmitNext("{\"type\":\"error\",\"message\":\""
                                                            + escapeJson(event.message()) + "\"}");
                                                }
                                                default -> {
                                                }
                                            }
                                        })
                                        .publishOn(Schedulers.boundedElastic())
                                        .doFinally(signal -> {
                                            int durationMs = (int) (System.currentTimeMillis() - startMs);
                                            int thinkingDurationMs = (int) (thinkingDurationAcc[0]
                                                    + (thinkingStartMs[0] > 0
                                                    ? System.currentTimeMillis() - thinkingStartMs[0] : 0));
                                            prep.assistantMsg.setContent(contentAcc.toString());
                                            prep.assistantMsg.setStatus(
                                                    signal == reactor.core.publisher.SignalType.ON_COMPLETE
                                                            ? MessageStatus.COMPLETED : MessageStatus.FAILED);
                                            prep.assistantMsg.setModel(prep.modelName);
                                            if (totalTokens[0] > 0)
                                                prep.assistantMsg.setTotalTokens(totalTokens[0]);
                                            prep.assistantMsg.setDurationMs(durationMs);
                                            if (thinkingTokens[0] > 0)
                                                prep.assistantMsg.setThinkingTokens(thinkingTokens[0]);
                                            if (thinkingDurationMs > 0)
                                                prep.assistantMsg.setThinkingDurationMs(thinkingDurationMs);
                                            messageRepository.save(prep.assistantMsg);
                                            log.debug("[持久化] 助理消息已保存: msgId={}, status={}, contentLen={}",
                                                    prep.msgId, prep.assistantMsg.getStatus(), contentAcc.length());
                                            eventPublisher.publishEvent(new MessageCompletedEvent(
                                                    prep.msgId, conversationId, accountId,
                                                    conversation.getProjectId(), contentAcc.toString(), null,
                                                    null, 0, 0, 0));
                                            prep.sink.tryEmitNext(SseFormatter.buildMetaEvent(
                                                    prep.modelName, totalTokens[0] > 0 ? totalTokens[0] : null,
                                                    durationMs, thinkingDurationMs,
                                                    thinkingTokens[0] > 0 ? thinkingTokens[0] : null,
                                                    prep.msgId.toString(), null));
                                            streamManager.complete(prep.msgId);
                                        })
                                        .subscribeOn(Schedulers.boundedElastic())
                                        .subscribe();

                                return Mono.empty();
                            });
                })
                .onErrorResume(e -> {
                    log.error("Forest 执行流异常: conversationId={}", conversationId, e);
                    prep.assistantMsg.setContent("执行失败: " + e.getMessage());
                    prep.assistantMsg.setStatus(MessageStatus.FAILED);
                    messageRepository.save(prep.assistantMsg);
                    prep.sink.tryEmitNext("{\"type\":\"error\",\"message\":\"" + escapeJson(prep.assistantMsg.getContent()) + "\"}");
                    streamManager.complete(prep.msgId);
                    return Mono.empty();
                })
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();

        return streamManager.subscribe(prep.msgId).doOnCancel(() -> onClientDisconnect(prep.msgId));
    }

    // ============ 重新生成 ============

    /**
     * 重新生成 AI 回复。重新走上下文构建 → LLM 流式调用。
     * 旧 AI 消息保留在 DB 中并分配 generationGroup（支持版本切换），
     * 但在构建 LLM 历史时排除。
     */
    public Flux<String> regenerateMessage(UUID accountId, UUID conversationId, RegenerateRequest request) {
        Conversation conversation = conversationCrudService.findOwned(accountId, conversationId);

        Message aiMsg = messageRepository.findById(request.getMessageId())
                .orElseThrow(() -> new DomainException("消息不存在: " + request.getMessageId()));
        if (!aiMsg.getConversationId().equals(conversationId)) {
            throw new DomainException("消息不属于该对话");
        }
        if (!aiMsg.isAssistant()) {
            throw new DomainException("只能重新生成 AI 回复");
        }

        int newGenIndex = (aiMsg.getGenerationIndex() != null ? aiMsg.getGenerationIndex() : 0) + 1;

        UUID generationGroup = aiMsg.getGenerationGroup() != null ? aiMsg.getGenerationGroup() : UUID.randomUUID();
        aiMsg.setGenerationGroup(generationGroup);
        messageRepository.save(aiMsg);

        // 获取原始用户消息
        Message userMsg = null;
        if (aiMsg.getReplyToId() != null) {
            userMsg = messageRepository.findById(aiMsg.getReplyToId()).orElse(null);
        }
        if (userMsg == null || !userMsg.isUser()) {
            int aiSortOrder = aiMsg.getSortOrder() != null ? aiMsg.getSortOrder() : Integer.MAX_VALUE;
            userMsg = messageRepository.findBeforeSortOrder(conversationId, aiSortOrder, 50).stream()
                    .filter(Message::isUser)
                    .filter(m -> m.getSortOrder() != null && m.getSortOrder() < aiSortOrder)
                    .findFirst().orElse(null);
        }
        String userContent = userMsg != null ? userMsg.getContent() : "";
        List<String> regenerateImages = userMsg != null
                ? MessageAttachmentsSerializer.deserializeImages(userMsg.getImages()) : null;
        List<String> regenerateAudios = userMsg != null
                ? MessageAttachmentsSerializer.deserializeAudios(userMsg.getAudios()) : null;

        UUID providerId = request.getModelProviderId();
        int contextLength = promptContextService.resolveContextLength(providerId, accountId);
        int maxPromptTokens = (int) (contextLength * promptContextService.resolveBudgetRatio(providerId, accountId));
        int historyBudget = (int) (maxPromptTokens * 0.85);

        Message assistantMsg = messageCrudService.createAssistantMessage(conversation, accountId);
        assistantMsg.setGenerationIndex(newGenIndex);
        assistantMsg.setGenerationGroup(generationGroup);
        assistantMsg = messageRepository.save(assistantMsg);
        final var finalAssistantMsg = assistantMsg;

        UUID msgId = assistantMsg.getMessageId();
        Sinks.Many<String> sink = streamManager.create(msgId);

        log.info("重新生成编排流: conversationId={}, msgId={}, providerId={}, contextLength={}",
                conversationId, msgId, providerId, contextLength);

        Consumer<String> progress = msg -> log.debug("重新生成编排进度: {}", msg);

        conversationCompressionService.resolveCompressResult(conversation, conversationId, historyBudget,
                        accountId, progress, userContent)
                .flatMap(compressResult -> {
                    try {
                        ContextResult epochResult = null;
                        String enhancedContext;
                        try {
                            epochResult = promptContextService.buildEpochContextResult(
                                    conversationId, compressResult, historyBudget, null);
                            enhancedContext = (epochResult != null && !epochResult.isEmpty())
                                    ? epochResult.toFlatString()
                                    : compressResult.toSummaryText();
                        } catch (Exception e) {
                            log.warn("ContextBuilder 增强失败(regenerate)，降级到压缩摘要", e);
                            enhancedContext = compressResult.toSummaryText();
                        }
                        List<Msg> prebuiltMsgs = promptContextService.buildLlmMessages(conversationId, accountId,
                                regenerateImages, regenerateAudios, contextLength,
                                aiMsg.getMessageId(), null, null, null, providerId);
                        PromptContextService.insertContextMessages(prebuiltMsgs, epochResult, enhancedContext);

                        TaskNode tree = new TaskNode(userContent);
                        String projectPath = groupService.getProjectRootPath(accountId, conversation.getProjectId());
                        Forest forest = persistForestTree(tree, userContent,
                                accountId, conversation.getProjectId());
                        forestCompressor.compress(forest, tree, "send", contextLength);
                        tree.metadata().put("prebuiltMessages", prebuiltMsgs);
                        if (projectPath != null) tree.metadata().put("_fileRootPath", projectPath);
                        ExecutionContext execCtx = ExecutionContext.create(accountId,
                                conversation.getProjectId(), conversationId);

                        StringBuilder contentAcc = new StringBuilder();
                        long startMs = System.currentTimeMillis();
                        final int[] totalTokens = {0};
                        final int[] thinkingTokens = {0};
                        final long[] thinkingStartMs = {0};
                        final long[] thinkingDurationAcc = {0};
                        String modelName = resolveModelName(providerId, accountId);

                        forestExecutor.execute(tree, execCtx, Delivery.SUMMARY)
                                .doOnNext(event -> {
                                    switch (event.type()) {
                                        case DETAIL -> {
                                            if (thinkingStartMs[0] > 0) {
                                                thinkingDurationAcc[0] += System.currentTimeMillis() - thinkingStartMs[0];
                                                thinkingStartMs[0] = 0;
                                            }
                                            contentAcc.append(event.message());
                                            sink.tryEmitNext(SseFormatter.toJsonEvent("response", event.message()));
                                        }
                                        case THINKING -> {
                                            if (thinkingStartMs[0] == 0)
                                                thinkingStartMs[0] = System.currentTimeMillis();
                                            sink.tryEmitNext(SseFormatter.toJsonEvent("thinking", event.message()));
                                        }
                                        case MILESTONE -> {
                                            String m = event.message();
                                            try {
                                                totalTokens[0] = Integer.parseInt(
                                                        m.replaceAll(".*\"totalTokens\":(\\d+).*", "$1"));
                                            } catch (Exception ignored) {
                                            }
                                            try {
                                                thinkingTokens[0] = Integer.parseInt(
                                                        m.replaceAll(".*\"thinkingTokens\":(\\d+).*", "$1"));
                                            } catch (Exception ignored) {
                                            }
                                        }
                                        case ERROR -> {
                                            sink.tryEmitNext("{\"type\":\"error\",\"message\":\""
                                                    + escapeJson(event.message()) + "\"}");
                                        }
                                    }
                                })
                                .publishOn(Schedulers.boundedElastic())
                                .doFinally(signal -> {
                                    int durationMs = (int) (System.currentTimeMillis() - startMs);
                                    int thinkingDurationMs = (int) (thinkingDurationAcc[0]
                                            + (thinkingStartMs[0] > 0
                                            ? System.currentTimeMillis() - thinkingStartMs[0] : 0));
                                    finalAssistantMsg.setContent(contentAcc.toString());
                                    finalAssistantMsg.setStatus(
                                            signal == reactor.core.publisher.SignalType.ON_COMPLETE
                                                    ? MessageStatus.COMPLETED : MessageStatus.FAILED);
                                    finalAssistantMsg.setModel(modelName);
                                    if (totalTokens[0] > 0)
                                        finalAssistantMsg.setTotalTokens(totalTokens[0]);
                                    finalAssistantMsg.setDurationMs(durationMs);
                                    if (thinkingTokens[0] > 0)
                                        finalAssistantMsg.setThinkingTokens(thinkingTokens[0]);
                                    if (thinkingDurationMs > 0)
                                        finalAssistantMsg.setThinkingDurationMs(thinkingDurationMs);
                                    messageRepository.save(finalAssistantMsg);
                                    log.debug("[持久化] 重新生成消息已保存: msgId={}, status={}, contentLen={}",
                                            msgId, finalAssistantMsg.getStatus(), contentAcc.length());
                                    eventPublisher.publishEvent(new MessageCompletedEvent(
                                            msgId, conversationId, accountId,
                                            conversation.getProjectId(), contentAcc.toString(), null,
                                            null, 0, 0, 0));
                                    sink.tryEmitNext(SseFormatter.buildMetaEvent(
                                            modelName, totalTokens[0] > 0 ? totalTokens[0] : null,
                                            durationMs, thinkingDurationMs,
                                            thinkingTokens[0] > 0 ? thinkingTokens[0] : null,
                                            msgId.toString(), null));
                                    streamManager.complete(msgId);
                                })
                                .subscribeOn(Schedulers.boundedElastic())
                                .subscribe();

                    } catch (Exception e) {
                        log.error("重新生成执行异常: conversationId={}", conversationId, e);
                        finalAssistantMsg.setContent("执行失败: " + e.getMessage());
                        finalAssistantMsg.setStatus(MessageStatus.FAILED);
                        messageRepository.save(finalAssistantMsg);
                        sink.tryEmitNext("{\"type\":\"response\",\"content\":\""
                                + escapeJson(e.getMessage()) + "\"}");
                        streamManager.complete(msgId);
                    }
                    return Mono.empty();
                })
                .onErrorResume(e -> {
                    log.error("重新生成流异常: conversationId={}", conversationId, e);
                    finalAssistantMsg.setContent("执行失败: " + e.getMessage());
                    finalAssistantMsg.setStatus(MessageStatus.FAILED);
                    messageRepository.save(finalAssistantMsg);
                    sink.tryEmitNext("{\"type\":\"response\",\"content\":\""
                            + escapeJson(finalAssistantMsg.getContent()) + "\"}");
                    streamManager.complete(msgId);
                    return Mono.empty();
                })
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();

        return streamManager.subscribe(msgId).doOnCancel(() -> onClientDisconnect(msgId));
    }

    // ============ 流订阅与取消 ============

    /**
     * 续接指定消息的流。优先从活跃的 Stream Sink 订阅（续接真实流），
     * Sink 不存在时回退 DB 持久化内容。
     */
    public Flux<String> subscribeStream(UUID accountId, UUID conversationId, UUID messageId) {
        Message message = messageRepository.findById(messageId)
                .orElseThrow(() -> ResourceNotFoundException.notFound("消息", messageId));
        if (!message.getConversationId().equals(conversationId) || !message.getAccountId().equals(accountId)) {
            throw ResourceNotFoundException.notFound("消息", messageId);
        }

        if (streamManager.isActive(messageId)) {
            return streamManager.subscribe(messageId);
        }

        if (message.getStatus() == MessageStatus.COMPLETED) {
            return Flux.just(
                    SseFormatter.toJsonEvent("response",
                            message.getContent() != null ? message.getContent() : ""),
                    SseFormatter.buildMetaEvent(message.getModel(), message.getTotalTokens(),
                            message.getDurationMs() != null ? message.getDurationMs() : 0,
                            message.getThinkingDurationMs() != null ? message.getThinkingDurationMs() : 0,
                            message.getMessageId() != null ? message.getMessageId().toString() : null,
                            message.getChain()));
        }

        if (message.getContent() != null && !message.getContent().isEmpty()) {
            return Flux.just(SseFormatter.toJsonEvent("response", message.getContent()));
        }
        return Flux.empty();
    }

    /**
     * 取消指定消息的后台流（API 调用入口，含所有权校验）。
     */
    public void cancelStream(UUID accountId, UUID conversationId, UUID messageId) {
        conversationCrudService.findOwned(accountId, conversationId);
        cleanupStream(messageId);
    }

    /**
     * 取消指定消息的后台流：释放 SSE sink，标记消息为已取消。
     */
    private void cleanupStream(UUID messageId) {
        streamManager.complete(messageId);
        streamManager.remove(messageId);
        messageRepository.findById(messageId).ifPresent(msg -> {
            if (msg.getStatus() != MessageStatus.COMPLETED) {
                msg.setStatus(MessageStatus.CANCELLED);
                messageRepository.save(msg);
            }
        });
    }

    private void onClientDisconnect(UUID messageId) {
        log.info("SSE 客户端断开连接，后台任务继续执行: msgId={}", messageId);
    }

    // ============ 进度查询 ============

    @SuppressWarnings("unchecked")
    public Map<String, Object> getMessageProgress(UUID accountId, UUID conversationId, UUID messageId) {
        conversationCrudService.findOwned(accountId, conversationId);
        var opt = messageRepository.findById(messageId);
        if (opt.isEmpty()) {
            return java.util.Collections.emptyMap();
        }
        Message msg = opt.get();
        String progressJson = msg.getProgress();
        if (progressJson == null || progressJson.isBlank()) {
            return java.util.Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(progressJson, Map.class);
        } catch (Exception e) {
            log.warn("解析 progress JSON 失败: msgId={}", messageId, e);
            return java.util.Collections.emptyMap();
        }
    }

    // ============ 内部辅助 ============

    private static String escapeJson(String s) {
        return JsonUtil.escapeJson(s);
    }

    private Forest persistForestTree(ExecutableNode root, String content, UUID accountId, UUID projectId) {
        Forest forest = new Forest(UUID.randomUUID(), accountId, projectId,
                content.length() > 50 ? content.substring(0, 47) + "..." : content,
                root.nodeId());
        try {
            transactionTemplate.executeWithoutResult(status -> {
                forestRepository.saveForest(forest, accountId);
                forestRepository.saveTree(root, forest.forestId(), accountId);
            });
        } catch (Exception e) {
            log.warn("持久化执行树失败（不影响执行）: forestId={} projectId={} error={}",
                    root.nodeId(), projectId, e.getMessage());
        }
        return forest;
    }

    private void addRuntimeMetadata(ExecutableNode node, List<Msg> msgs, List<ToolSpec> tools, UUID forestId, String fileRootPath) {
        if (node instanceof TaskNode tn) {
            tn.metadata().put("prebuiltMessages", msgs);
            tn.metadata().put("_forestId", forestId != null ? forestId.toString() : "");
            if (tools != null) tn.metadata().put("preferredToolSpecs", tools);
            if (fileRootPath != null) tn.metadata().put("_fileRootPath", fileRootPath);
        }
        for (var child : node.children()) {
            if (child instanceof ExecutableNode en) {
                addRuntimeMetadata(en, msgs, tools, forestId, fileRootPath);
            }
        }
    }
}
