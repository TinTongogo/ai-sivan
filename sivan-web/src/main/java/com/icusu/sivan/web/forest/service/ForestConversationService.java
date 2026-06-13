package com.icusu.sivan.web.forest.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.icusu.sivan.agent.model.ModelRouter;
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
import com.icusu.sivan.domain.forest.context.Delivery;
import com.icusu.sivan.domain.forest.context.ExecutionContext;
import com.icusu.sivan.domain.forest.service.ForestRepository;
import com.icusu.sivan.domain.forest.service.TreeMatcher;
import com.icusu.sivan.domain.forest.tree.ExecutableNode;
import com.icusu.sivan.domain.forest.tree.TaskNode;
import com.icusu.sivan.domain.memory.InstinctPattern;
import com.icusu.sivan.domain.shared.event.MessageCompletedEvent;
import com.icusu.sivan.domain.task.TaskFeatures;
import com.icusu.sivan.agent.routing.RoutingDecisionRecorder;
import com.icusu.sivan.agent.routing.engine.PgRouteEngine;
import com.icusu.sivan.domain.forest.tree.TaskNode;
import com.icusu.sivan.domain.routing.RouteResult;
import com.icusu.sivan.infra.forest.repository.ForestAgentMessageJpaRepository;
import com.icusu.sivan.infra.forest.compression.ForestCompressor;
import com.icusu.sivan.infra.forest.execution.ForestExecutor;
import com.icusu.sivan.infra.forest.execution.GoalExecutionService;
import com.icusu.sivan.infra.forest.sink.ForestMetricsCollector;
import com.icusu.sivan.infra.memory.instinct.InstinctPatternService;
import com.icusu.sivan.infra.shared.sse.SseFormatter;
import com.icusu.sivan.infra.shared.sse.StreamManager;
import com.icusu.sivan.web.forest.dto.ForestTreeResponse;
import com.icusu.sivan.web.conversation.dto.*;
import com.icusu.sivan.web.conversation.service.ConversationCompressionService;
import com.icusu.sivan.web.conversation.service.ConversationCrudService;
import com.icusu.sivan.web.conversation.service.MessageCrudService;
import com.icusu.sivan.web.conversation.service.PromptContextService;
import com.icusu.sivan.web.conversation.service.PromptContextService.ChatToolResult;
import com.icusu.sivan.web.conversation.service.message.MessageAttachmentsSerializer;
import com.icusu.sivan.web.conversation.service.tree.ContextResult;
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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
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
    private final GoalExecutionService goalExecutionService;
    private final ForestCompressor forestCompressor;
    private final PgRouteEngine pgRouteEngine;
    private final ForestAgentMessageJpaRepository a2aMessageRepo;
    private final RoutingDecisionRecorder routingDecisionRecorder;
    private final ConversationCrudService conversationCrudService;
    private final MessageCrudService messageCrudService;
    private final PromptContextService promptContextService;
    private final ModelRouter modelRouter;
    private final GroupService groupService;
    private final ForestRepository forestRepository;
    private final TransactionTemplate transactionTemplate;
    private final ApplicationEventPublisher eventPublisher;
    private final ForestMetricsCollector metricsCollector;
    private final com.icusu.sivan.domain.agent.IAgentRepository agentRepository;
    private final InstinctPatternService instinctPatternService;
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

    /** 查询对话关联的 GoalTree 列表（14-对话管理 §4）。 */
    public List<Forest> getForestsByConversation(UUID accountId, UUID conversationId) {
        conversationCrudService.findOwned(accountId, conversationId);
        return forestRepository.findByConversationId(conversationId, accountId);
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
        UUID resolvedReplyToId = messageCrudService.resolveLatestGeneration(request.getReplyToId());
        Message userMessage = Message.builder()
                .conversationId(conversationId)
                .accountId(accountId)
                .projectId(conversation.getProjectId())
                .role(Message.ROLE_USER)
                .content(request.getContent())
                .contentType(request.getContentType() != null ? request.getContentType() : "text")
                .targetAgent(request.getTargetAgent())
                .replyToId(resolvedReplyToId)
                .status(MessageStatus.COMPLETED)
                .images(MessageAttachmentsSerializer.serializeImages(request.getImages()))
                .audios(MessageAttachmentsSerializer.serializeAudios(request.getAudios()))
                .attachments(MessageAttachmentsSerializer.serializeAttachments(request.getAttachments()))
                .build();
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

                    // 本能模板匹配：优先复用历史成功的任务分解
                    TaskFeatures features = extractTaskFeatures(request.getContent());
                    final AtomicReference<UUID> patternIdRef = new AtomicReference<>();
                    Optional<InstinctPattern> patternOpt = instinctPatternService.match(features, accountId);
                    Mono<ExecutableNode> treeMono;
                    if (patternOpt.isPresent()) {
                        InstinctPattern p = patternOpt.get();
                        ExecutableNode cachedTree = parseTopologyJson(p.getTopologyJson(), request.getContent());
                        if (cachedTree != null) {
                            log.info("本能模板命中: patternId={} mode={}", p.getPatternId(), p.getExecutionMode());
                            patternIdRef.set(p.getPatternId());
                            // 发射模板匹配 SSE 事件
                            String sr = p.getSuccessRate() != null
                                    ? String.format("%.0f%%", p.getSuccessRate() * 100) : "N/A";
                            prep.sink.tryEmitNext(SseFormatter.buildMatchTemplateEvent(
                                    "已匹配模板「" + p.getExecutionMode() + "」- 成功率 " + sr
                                            + "（" + (p.getTotalCount() != null ? p.getTotalCount() : 0) + "次）"));
                            treeMono = Mono.just(cachedTree);
                        } else {
                            treeMono = treeMatcher.match(request.getContent(), accountId);
                        }
                    } else {
                        treeMono = treeMatcher.match(request.getContent(), accountId);
                    }

                    return treeMono
                            .switchIfEmpty(Mono.fromRunnable(() -> {
                                prep.assistantMsg.setContent("执行树构建失败");
                                prep.assistantMsg.setStatus(MessageStatus.FAILED);
                                messageRepository.save(prep.assistantMsg);
                                streamManager.complete(prep.msgId);
                            }))
                            .flatMap(tree -> {
                                String projectPath = groupService.getProjectRootPath(accountId, conversation.getProjectId());

                                // 先创建 Forest（获取 forestId），再设 metadata，再持久化
                                Forest forest = new Forest(UUID.randomUUID(), accountId,
                                        conversation.getProjectId(), conversationId,
                                        request.getContent().length() > 50
                                                ? request.getContent().substring(0, 47) + "..."
                                                : request.getContent(),
                                        tree.nodeId());

                                forestCompressor.compress(forest, tree, "send", prep.maxPromptTokens);
                                String agent = request.getTargetAgent();
                                List<String> convKbIds = conversation.getKnowledgeBaseIds();
                                addRuntimeMetadata(tree, prebuiltMsgs, coreTools, forest.forestId(),
                                        projectPath, accountId, agent, convKbIds, conversationId);

                                // 持久化（metadata 已写入，一起存库）
                                persistForest(forest, tree, accountId);

                                ExecutionContext execCtx = ExecutionContext.create(accountId,
                                        conversation.getProjectId(), conversationId);

                                StringBuilder contentAcc = new StringBuilder();
                                StringBuilder thinkingAcc = new StringBuilder();
                                long startMs = System.currentTimeMillis();

                                final int[] totalTokens = {0};
                                final int[] thinkingTokens = {0};
                                final long[] thinkingStartMs = {0};
                                final long[] thinkingDurationAcc = {0};
                                final java.util.Set<String> activePhases = new java.util.HashSet<>();
                                final int[] activeCount = {0};
                                final int[] completedCount = {0};
                                final int totalLeaves = countLeaves(tree);
                                final java.util.Map<String, String> agentNameByNodeId = buildNodeAgentMap(tree);
                                final long startProgressMs = System.currentTimeMillis();

                                goalExecutionService.executeOnly(forest, tree, execCtx, Delivery.SUMMARY)
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
                                                    thinkingAcc.append(event.message());
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
                                                case LIFECYCLE -> {
                                                    String nid = event.nodeId();
                                                    if (activePhases.add(nid)) {
                                                        if (++activeCount[0] == 1) {
                                                            prep.sink.tryEmitNext(SseFormatter.buildPhaseStartEvent(
                                                                    "执行", null, null, 0, totalLeaves));
                                                        }
                                                        // 注入 Agent 分隔头
                                                        String agentName = agentNameByNodeId.get(nid);
                                                        if (agentName != null) {
                                                            String header = "\n--- " + agentName + " ---\n";
                                                            contentAcc.append(header);
                                                            prep.sink.tryEmitNext(SseFormatter.toJsonEvent("response", header));
                                                        }
                                                    } else {
                                                        activePhases.remove(nid);
                                                        completedCount[0]++;
                                                        if (--activeCount[0] > 0) {
                                                            prep.sink.tryEmitNext(buildProgressJson(
                                                                    completedCount[0], totalLeaves,
                                                                    System.currentTimeMillis() - startProgressMs));
                                                        } else {
                                                            prep.sink.tryEmitNext(SseFormatter.buildPhaseEndEvent(
                                                                    "执行", null, totalTokens[0] > 0 ? totalTokens[0] : null,
                                                                    null, null));
                                                        }
                                                    }
                                                }
                                                case BRANCH_DECISION -> {
                                                    prep.sink.tryEmitNext("{\"type\":\"branch_decision\",\"data\":"
                                                            + escapeJson(event.message()) + "}");
                                                }
                                                case HITL_RESUME -> {
                                                    prep.sink.tryEmitNext("{\"type\":\"hitl_resume\",\"nodeId\":\""
                                                            + event.nodeId() + "\",\"reason\":\""
                                                            + escapeJson(event.message()) + "\"}");
                                                }
                                                case HITL_REJECT -> {
                                                    prep.sink.tryEmitNext("{\"type\":\"hitl_reject\",\"nodeId\":\""
                                                            + event.nodeId() + "\",\"reason\":\""
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
                                            String thinkingContent = thinkingAcc.length() > 0 ? thinkingAcc.toString() : null;
                                            prep.assistantMsg.setContent(contentAcc.toString());
                                            prep.assistantMsg.setThinking(thinkingContent);
                                            prep.assistantMsg.setStatus(
                                                    signal == reactor.core.publisher.SignalType.ON_COMPLETE
                                                            ? MessageStatus.COMPLETED : MessageStatus.FAILED);
                                            prep.assistantMsg.setModel(prep.modelName);
                                            if (totalTokens[0] > 0)
                                                prep.assistantMsg.setTotalTokens(totalTokens[0]);
                                            prep.assistantMsg.setDurationMs(durationMs);
                                            prep.assistantMsg.setChain(forest.forestId().toString());
                                            if (thinkingTokens[0] > 0)
                                                prep.assistantMsg.setThinkingTokens(thinkingTokens[0]);
                                            if (thinkingDurationMs > 0)
                                                prep.assistantMsg.setThinkingDurationMs(thinkingDurationMs);
                                            messageRepository.save(prep.assistantMsg);
                                            log.debug("[持久化] 助理消息已保存: msgId={}, status={}, contentLen={} thinkLen={}",
                                                    prep.msgId, prep.assistantMsg.getStatus(), contentAcc.length(),
                                                    thinkingAcc.length());
                                            boolean success = signal == reactor.core.publisher.SignalType.ON_COMPLETE;
                                            metricsCollector.recordExecution(totalTokens[0], durationMs);
                                            // 本能模板学习：记录执行结果
                                            if (patternIdRef.get() != null) {
                                                instinctPatternService.recordResult(patternIdRef.get(), success, features);
                                            }
                                            eventPublisher.publishEvent(new MessageCompletedEvent(
                                                    prep.msgId, conversationId, accountId,
                                                    conversation.getProjectId(), contentAcc.toString(), thinkingContent,
                                                    prep.modelName, totalTokens[0], durationMs, thinkingDurationMs));
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
                        Forest forest = new Forest(UUID.randomUUID(), accountId,
                                conversation.getProjectId(), conversationId,
                                userContent.length() > 50 ? userContent.substring(0, 47) + "..." : userContent,
                                tree.nodeId());
                        forestCompressor.compress(forest, tree, "send", contextLength);
                        tree.metadata().put("prebuiltMessages", prebuiltMsgs);
                        tree.metadata().put("_accountId", accountId.toString());
                        if (projectPath != null) tree.metadata().put("_fileRootPath", projectPath);
                        List<String> convKbIds = conversation.getKnowledgeBaseIds();
                        if (convKbIds != null && !convKbIds.isEmpty()) {
                            tree.metadata().put("_kbNames", String.join(",", convKbIds));
                        }
                        addRuntimeMetadata(tree, prebuiltMsgs, null, forest.forestId(),
                                projectPath, accountId, null, convKbIds, conversationId);
                        persistForest(forest, tree, accountId);
                        ExecutionContext execCtx = ExecutionContext.create(accountId,
                                conversation.getProjectId(), conversationId);

                        StringBuilder contentAcc = new StringBuilder();
                        StringBuilder thinkingAcc = new StringBuilder();
                        long startMs = System.currentTimeMillis();
                        final int[] totalTokens = {0};
                        final int[] thinkingTokens = {0};
                        final long[] thinkingStartMs = {0};
                        final long[] thinkingDurationAcc = {0};
                        final java.util.Set<String> regActivePhases = new java.util.HashSet<>();
                        final int[] regActiveCount = {0};
                        final int[] regCompletedCount = {0};
                        final int regTotalLeaves = countLeaves(tree);
                        final java.util.Map<String, String> regAgentNameByNodeId = buildNodeAgentMap(tree);
                        final long regStartMs = System.currentTimeMillis();
                        String modelName = resolveModelName(providerId, accountId);

                        goalExecutionService.executeOnly(forest, tree, execCtx, Delivery.SUMMARY)
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
                                            thinkingAcc.append(event.message());
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
                                        case LIFECYCLE -> {
                                            String nid = event.nodeId();
                                            if (regActivePhases.add(nid)) {
                                                if (++regActiveCount[0] == 1) {
                                                    sink.tryEmitNext(SseFormatter.buildPhaseStartEvent(
                                                            "执行", null, null, 0, regTotalLeaves));
                                                }
                                                String regAgentName = regAgentNameByNodeId.get(nid);
                                                if (regAgentName != null) {
                                                    String header = "\n--- " + regAgentName + " ---\n";
                                                    contentAcc.append(header);
                                                    sink.tryEmitNext(SseFormatter.toJsonEvent("response", header));
                                                }
                                            } else {
                                                regActivePhases.remove(nid);
                                                regCompletedCount[0]++;
                                                if (--regActiveCount[0] > 0) {
                                                    sink.tryEmitNext(buildProgressJson(regCompletedCount[0],
                                                            regTotalLeaves,
                                                            System.currentTimeMillis() - regStartMs));
                                                } else {
                                                    sink.tryEmitNext(SseFormatter.buildPhaseEndEvent(
                                                            "执行", null, totalTokens[0] > 0 ? totalTokens[0] : null,
                                                            null, null));
                                                }
                                            }
                                        }
                                    }
                                })
                                .publishOn(Schedulers.boundedElastic())
                                .doFinally(signal -> {
                                    int durationMs = (int) (System.currentTimeMillis() - startMs);
                                    int thinkingDurationMs = (int) (thinkingDurationAcc[0]
                                            + (thinkingStartMs[0] > 0
                                            ? System.currentTimeMillis() - thinkingStartMs[0] : 0));
                                    String thinkingContent = thinkingAcc.length() > 0 ? thinkingAcc.toString() : null;
                                    finalAssistantMsg.setContent(contentAcc.toString());
                                    finalAssistantMsg.setThinking(thinkingContent);
                                    finalAssistantMsg.setStatus(
                                            signal == reactor.core.publisher.SignalType.ON_COMPLETE
                                                    ? MessageStatus.COMPLETED : MessageStatus.FAILED);
                                    finalAssistantMsg.setModel(modelName);
                                    if (totalTokens[0] > 0)
                                        finalAssistantMsg.setTotalTokens(totalTokens[0]);
                                    finalAssistantMsg.setDurationMs(durationMs);
                                    finalAssistantMsg.setChain(forest.forestId().toString());
                                    if (thinkingTokens[0] > 0)
                                        finalAssistantMsg.setThinkingTokens(thinkingTokens[0]);
                                    if (thinkingDurationMs > 0)
                                        finalAssistantMsg.setThinkingDurationMs(thinkingDurationMs);
                                    messageRepository.save(finalAssistantMsg);
                                    log.debug("[持久化] 重新生成消息已保存: msgId={}, status={}, contentLen={} thinkLen={}",
                                            msgId, finalAssistantMsg.getStatus(), contentAcc.length(),
                                            thinkingAcc.length());
                                    metricsCollector.recordExecution(totalTokens[0], durationMs);
                                    eventPublisher.publishEvent(new MessageCompletedEvent(
                                            msgId, conversationId, accountId,
                                            conversation.getProjectId(), contentAcc.toString(), thinkingContent,
                                            modelName, totalTokens[0], durationMs, thinkingDurationMs));
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

        // 取消 SSE 流 + 标记消息
        cleanupStream(messageId);

        // 取消 Forest 执行
        messageRepository.findById(messageId).ifPresent(msg -> {
            String chain = msg.getChain();
            if (chain != null && !chain.isBlank()) {
                try {
                    UUID forestId = UUID.fromString(chain);
                    goalExecutionService.cancelExecution(forestId);
                    log.info("[取消] Forest 执行已取消: forestId={} msgId={}", chain.substring(0, 8), messageId);
                } catch (Exception e) {
                    log.warn("[取消] 取消失败: {}", e.getMessage());
                }
            }
        });
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

    /**
     * 获取消息的 Forest 执行树。
     * 通过 conversationId 查找关联的 Forest，再获取完整的执行树结构。
     */
    public ForestTreeResponse getMessageForest(UUID accountId, UUID conversationId, UUID messageId) {
        conversationCrudService.findOwned(accountId, conversationId);

        // 先从消息的 chain 字段获取 forestId
        Forest forest = messageRepository.findById(messageId)
                .map(msg -> {
                    String chain = msg.getChain();
                    if (chain != null && !chain.isBlank()) {
                        try {
                            return forestRepository.findForestById(UUID.fromString(chain), accountId);
                        } catch (Exception e) {
                            log.debug("chain 中的 forestId 无效: {}", chain);
                        }
                    }
                    return null;
                })
                .orElse(null);

        // 回退：按对话查找最近的 Forest
        if (forest == null) {
            java.util.List<Forest> forests = forestRepository.findByConversationId(conversationId, accountId);
            if (forests.isEmpty()) {
                log.debug("无 Forest 记录: conversationId={}", conversationId);
                return null;
            }
            forest = forests.getFirst();
        }
        String rootNodeId = forest.rootNodeId();
        if (rootNodeId == null) return null;

        // 加载子树
        com.icusu.sivan.domain.forest.tree.TreeNode root = forestRepository.findSubtree(rootNodeId, accountId);
        if (root == null) return null;

        // 构建进度
        int total = countForestLeaves(root);
        int completed = countCompletedLeaves(root);

        ForestTreeResponse resp = new ForestTreeResponse();
        resp.setForestId(forest.forestId().toString());
        resp.setRoot(toForestTreeNode(root));
        resp.setProgress(new ForestTreeResponse.Progress(completed, total));
        return resp;
    }

    private ForestTreeResponse.TreeNode toForestTreeNode(com.icusu.sivan.domain.forest.tree.TreeNode node) {
        ForestTreeResponse.TreeNode tn = new ForestTreeResponse.TreeNode();
        tn.setNodeId(node.nodeId());
        tn.setName(node instanceof com.icusu.sivan.domain.forest.tree.ContentNode cn ? cn.content() : node.nodeType());
        tn.setLeaf(node.isLeaf());
        if (node instanceof com.icusu.sivan.domain.forest.tree.ExecutableNode en) {
            tn.setStatus(en.status().name());
            tn.setMode(en.mode() != com.icusu.sivan.common.Mode.NONE ? en.mode().name() : null);
        } else {
            tn.setStatus("PENDING");
        }
        if (node instanceof com.icusu.sivan.domain.forest.tree.ContentNode cn) {
            Object agentName = cn.metadata().get("agentName");
            if (agentName instanceof String s) tn.setAgent(s);
            Object routeTier = cn.metadata().get("_routeTier");
            if (routeTier instanceof Integer i) tn.setRouteTier(i);
            Object routeConf = cn.metadata().get("_routeConfidence");
            if (routeConf instanceof Double d) tn.setRouteConfidence(d);
        }
        // 加载 A2A 消息
        String nid = node.nodeId();
        if (a2aMessageRepo != null) {
            try {
                var a2aList = a2aMessageRepo.findByScopeNodeId(nid);
                if (a2aList != null && !a2aList.isEmpty()) {
                    tn.setA2aMessages(a2aList.stream()
                            .map(e -> new ForestTreeResponse.A2aMessage(
                                    e.getSourceAgent(), e.getTargetAgent(),
                                    e.getTopic(), e.getMessageType()))
                            .toList());
                }
            } catch (Exception e) {
                log.debug("加载 A2A 消息失败: nodeId={}", nid);
            }
        }
        if (!node.children().isEmpty()) {
            tn.setChildren(node.children().stream()
                    .filter(c -> c instanceof com.icusu.sivan.domain.forest.tree.ExecutableNode)
                    .map(this::toForestTreeNode)
                    .toList());
        }
        return tn;
    }

    private static int countForestLeaves(com.icusu.sivan.domain.forest.tree.TreeNode node) {
        if (node.isLeaf()) return 1;
        return node.children().stream()
                .filter(c -> c instanceof com.icusu.sivan.domain.forest.tree.ExecutableNode)
                .mapToInt(ForestConversationService::countForestLeaves)
                .sum();
    }

    private static int countCompletedLeaves(com.icusu.sivan.domain.forest.tree.TreeNode node) {
        if (node.isLeaf()) {
            return node instanceof com.icusu.sivan.domain.forest.tree.ExecutableNode en
                    && en.status() == com.icusu.sivan.common.NodeStatus.COMPLETED ? 1 : 0;
        }
        return node.children().stream()
                .filter(c -> c instanceof com.icusu.sivan.domain.forest.tree.ExecutableNode)
                .mapToInt(ForestConversationService::countCompletedLeaves)
                .sum();
    }

    // ============ 内部辅助 ============

    /**
     * 递归统计树中的叶子节点（TaskNode）数量。
     */
    private static int countLeaves(ExecutableNode node) {
        if (node.children().isEmpty()) return 1;
        int count = 0;
        for (var child : node.children()) {
            if (child instanceof ExecutableNode en) {
                count += countLeaves(en);
            }
        }
        return Math.max(count, 1);
    }

    /**
     * 构建 progress SSE 事件 JSON。
     */
    private static String buildProgressJson(int completed, int total, long elapsedMs) {
        return "{\"type\":\"progress\",\"data\":{\"status\":\"RUNNING\","
                + "\"totalPhases\":" + total
                + ",\"completedPhases\":" + completed
                + ",\"elapsedMs\":" + elapsedMs
                + ",\"totalTokens\":0"
                + ",\"currentPhase\":\"执行中\""
                + ",\"phases\":[]}}";
    }


    /**
     * 从用户输入中提取任务特征（启发式规则，用于本能模板匹配）。
     */
    private static TaskFeatures extractTaskFeatures(String input) {
        return TaskFeatures.fromContent(input);
    }

    /**
     * 将 InstinctPattern 的 topologyJson 解析为 ExecutableNode 树。复⽤ LlmTreeMatcher 的解析逻辑。
     */
    private ExecutableNode parseTopologyJson(String topologyJson, String originalInput) {
        if (topologyJson == null || topologyJson.isBlank()) return null;
        try {
            if (treeMatcher instanceof com.icusu.sivan.agent.forest.matcher.LlmTreeMatcher llm) {
                return llm.parseTree(topologyJson, originalInput);
            }
        } catch (Exception e) {
            log.warn("解析 topologyJson 失败: {}", e.getMessage());
        }
        return null;
    }

    private static String escapeJson(String s) {
        return JsonUtil.escapeJson(s);
    }

    /** 持久化已组装好 metadata 的 Forest + 树。 */
    private void persistForest(Forest forest, ExecutableNode root, UUID accountId) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                forestRepository.saveForest(forest, accountId);
                forestRepository.saveTree(root, forest.forestId(), accountId);
            });
        } catch (Exception e) {
            log.warn("持久化执行树失败（不影响执行）: forestId={} error={}", forest.forestId(), e.getMessage());
        }
    }

    private void addRuntimeMetadata(ExecutableNode node, List<Msg> msgs, List<ToolSpec> tools,
                                    UUID forestId, String fileRootPath, UUID accountId,
                                    String agentName, List<String> convKbIds, UUID conversationId) {
        if (node instanceof TaskNode tn) {
            tn.metadata().put("prebuiltMessages", msgs);
            tn.metadata().put("_forestId", forestId != null ? forestId.toString() : "");
            tn.metadata().put("_accountId", accountId.toString());
            if (tools != null) tn.metadata().put("preferredToolSpecs", tools);
            if (fileRootPath != null) tn.metadata().put("_fileRootPath", fileRootPath);
            if (convKbIds != null && !convKbIds.isEmpty()) {
                tn.metadata().put("_kbNames", String.join(",", convKbIds));
            }

            // PgRouteEngine 优化 Agent 分配，并记录路由决策
            String currentAgent = agentName;
            String taskContent = tn.content();
            if (taskContent != null && !taskContent.isBlank()) {
                String featureHash = PgRouteEngine.md5(TaskFeatures.fromContent(taskContent).toString());
                try {
                    RouteResult rr = Mono.fromCallable(() ->
                                pgRouteEngine.resolve(accountId, taskContent, featureHash).block())
                            .subscribeOn(Schedulers.boundedElastic()).block();
                    if (rr != null) {
                        if ("chat".equals(rr.intent())) {
                            // Chat 路径：切换节点类型为 "message"，不走 ReAct/工具
                            tn.setNodeType("message");
                            log.debug("[路由] chat 意图，切换 nodeType=message: content={}", truncateStr(taskContent, 40));
                        } else {
                            // Task 路径：正常分配 Agent + 路由决策
                            currentAgent = rr.agentName();
                            tn.metadata().put("_routeTier", rr.tier());
                            tn.metadata().put("_routeConfidence", rr.confidence());
                            log.debug("[路由] {} → {} (Tier {}, conf={})",
                                    agentName != null ? agentName : "未分配",
                                    currentAgent, rr.tier(), String.format("%.2f", rr.confidence()));

                            // 记录路由决策到数据库（路由日志展示用）
                            routingDecisionRecorder.record(new RoutingDecisionRecorder.RecordRequest(
                                    accountId, null, conversationId, taskContent,
                                    routeTierToStrategy(rr.tier()), rr.agentName(), true,
                                    "Tier " + rr.tier() + " · " + (rr.confidence() > 0
                                            ? "置信度 " + String.format("%.0f", rr.confidence() * 100) + "%"
                                            : "新建 Agent"),
                                    rr.confidence(), null, null));
                        }
                    }
                } catch (Exception e) {
                    log.warn("[路由] PgRouteEngine 异常(使用默认 Agent): {}", e.getMessage());
                }
            }
            // 兜底：确保 agentName 有值，让反馈回路（RouteFeedbackHandler）能触发
            if (currentAgent == null || currentAgent.isBlank()) {
                currentAgent = "通用助手";
            }
            if (currentAgent != null && !currentAgent.isBlank()) tn.metadata().put("agentName", currentAgent);
        }
        for (var child : node.children()) {
            if (child instanceof ExecutableNode en) {
                addRuntimeMetadata(en, msgs, tools, forestId, fileRootPath, accountId, agentName, convKbIds, conversationId);
            }
        }
    }

    /** 将路由层级转为策略名称，用于路由日志展示。 */
    private static String routeTierToStrategy(int tier) {
        return switch (tier) {
            case 0 -> "exact";
            case 1 -> "semantic";
            case 2 -> "explore";
            case 3 -> "auto_create";
            default -> "auto";
        };
    }

    private static String truncateStr(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }


    /** 遍历树，构建 nodeId → agentName 映射。 */
    private static java.util.Map<String, String> buildNodeAgentMap(
            com.icusu.sivan.domain.forest.tree.TreeNode root) {
        java.util.Map<String, String> map = new java.util.HashMap<>();
        collectAgentNames(root, map);
        return map;
    }

    @SuppressWarnings("unchecked")
    private static void collectAgentNames(
            com.icusu.sivan.domain.forest.tree.TreeNode node,
            java.util.Map<String, String> map) {
        if (node instanceof com.icusu.sivan.domain.forest.tree.ContentNode cn) {
            Object raw = cn.metadata().get("agentName");
            if (raw instanceof String s && !s.isBlank()) {
                map.put(node.nodeId(), s);
            }
        }
        for (com.icusu.sivan.domain.forest.tree.TreeNode child : node.children()) {
            collectAgentNames(child, map);
        }
    }
}
