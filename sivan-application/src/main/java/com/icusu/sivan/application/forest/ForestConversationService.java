package com.icusu.sivan.application.forest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.icusu.sivan.agent.forest.matcher.LlmTreeMatcher;
import com.icusu.sivan.agent.model.ModelRouter;
import com.icusu.sivan.agent.routing.RoutingDecisionRecorder;
import com.icusu.sivan.agent.routing.engine.PgRouteEngine;
import com.icusu.sivan.application.conversation.ConversationCompressionService;
import com.icusu.sivan.application.conversation.ConversationCrudService;
import com.icusu.sivan.application.conversation.MessageCrudService;
import com.icusu.sivan.application.conversation.PromptContextService;
import com.icusu.sivan.application.conversation.ToolResolutionService.ChatToolResult;
import com.icusu.sivan.application.conversation.dto.*;
import com.icusu.sivan.application.conversation.message.MessageAttachmentsSerializer;
import com.icusu.sivan.application.conversation.tree.ContextResult;
import com.icusu.sivan.application.forest.dto.ForestTreeResponse;
import com.icusu.sivan.application.service.GroupService;
import com.icusu.sivan.common.Mode;
import com.icusu.sivan.common.NodeStatus;
import com.icusu.sivan.common.enums.MessageStatus;
import com.icusu.sivan.common.exception.DomainException;
import com.icusu.sivan.common.exception.ResourceNotFoundException;
import com.icusu.sivan.common.util.JsonUtil;
import com.icusu.sivan.core.message.Msg;
import com.icusu.sivan.core.message.Role;
import com.icusu.sivan.core.tool.ToolSpec;
import com.icusu.sivan.domain.conversation.Conversation;
import com.icusu.sivan.domain.conversation.IConversationRepository;
import com.icusu.sivan.domain.conversation.IMessageRepository;
import com.icusu.sivan.domain.conversation.Message;
import com.icusu.sivan.domain.forest.Forest;
import com.icusu.sivan.domain.forest.context.Delivery;
import com.icusu.sivan.domain.forest.context.ExecutionContext;
import com.icusu.sivan.domain.forest.port.ForestRepository;
import com.icusu.sivan.domain.forest.port.TreeMatcher;
import com.icusu.sivan.domain.forest.tree.ContentNode;
import com.icusu.sivan.domain.forest.tree.ExecutableNode;
import com.icusu.sivan.domain.forest.tree.TreeNode;
import com.icusu.sivan.domain.forest.tree.node.TaskNode;
import com.icusu.sivan.domain.memory.InstinctPattern;
import com.icusu.sivan.domain.memory.TaskFeatures;
import com.icusu.sivan.domain.routing.RouteResult;
import com.icusu.sivan.domain.shared.event.MessageCompletedEvent;
import com.icusu.sivan.infra.forest.compression.ForestCompressor;
import com.icusu.sivan.infra.forest.execution.ForestExecutor;
import com.icusu.sivan.infra.forest.execution.GoalExecutionService;
import com.icusu.sivan.infra.forest.repository.ForestNodeJpaRepository;
import com.icusu.sivan.infra.forest.repository.ForestAgentMessageJpaRepository;
import com.icusu.sivan.infra.forest.entity.ForestExecutionLogEntity;
import com.icusu.sivan.infra.forest.repository.ForestExecutionLogJpaRepository;
import com.icusu.sivan.infra.forest.sink.ForestMetricsCollector;
import com.icusu.sivan.infra.memory.instinct.InstinctPatternService;
import com.icusu.sivan.infra.shared.sse.SseFormatter;
import com.icusu.sivan.infra.shared.sse.StreamManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.util.*;
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
    private final GoalExecutionService goalExecutionService;
    private final ForestCompressor forestCompressor;
    private final ForestExecutionOrchestrator forestExecutionOrchestrator;
    private final ForestRoutingService forestRoutingService;
    private final PgRouteEngine pgRouteEngine;
    private final ForestNodeJpaRepository forestNodeJpaRepository;
    private final ForestAgentMessageJpaRepository a2aMessageRepo;
    private final ForestExecutionLogJpaRepository executionLogRepo;
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

    /**
     * 查询对话关联的 GoalTree 列表（14-对话管理 §4）。
     */
    public List<Forest> getForestsByConversation(UUID accountId, UUID conversationId) {
        conversationCrudService.findOwned(accountId, conversationId);
        return forestRepository.findByConversationId(conversationId, accountId);
    }

    public int countMessages(UUID accountId, UUID conversationId) {
        return messageCrudService.countMessages(accountId, conversationId);
    }

    public MessageResponse rateMessage(UUID accountId, UUID messageId, String rating) {
        MessageResponse resp = messageCrudService.rateMessage(accountId, messageId, rating);
        // 踩 → 更新 Beta 参数，降低下次推荐同 Agent 的概率
        if ("dislike".equals(rating)) {
            String agentName = findExecutionAgentForMessage(messageId);
            if (agentName != null) {
                messageRepository.findById(messageId).ifPresent(msg ->
                    forestRoutingService.recordFeedback(accountId, agentName, msg.getContent(), false, null));
            }
        }
        return resp;
    }

    /**
     * 查找消息对应的执行 Agent 名称。
     * 优先从消息的父节点（助手消息→执行树根），其次从子节点（用户消息→执行树根）查找。
     */
    private String findExecutionAgentForMessage(UUID messageId) {
        try {
            var entity = forestNodeJpaRepository.findById(messageId.toString()).orElse(null);
            if (entity == null) return null;
            // 方向 1：助手消息 → 父节点可能是执行树根
            String parentId = entity.getParentNodeId();
            if (parentId != null) {
                var parent = forestNodeJpaRepository.findById(parentId).orElse(null);
                if (parent != null && isExecutionRoot(parent)) {
                    return extractAgentNameFromJson(parent.getMetadata());
                }
            }
            // 方向 2：用户消息 → 子节点中找执行树根
            var children = forestNodeJpaRepository.findByForestIdAndParentNodeIdOrderBySortOrderAsc(
                    entity.getForestId(), messageId.toString());
            for (var child : children) {
                if (isExecutionRoot(child)) {
                    return extractAgentNameFromJson(child.getMetadata());
                }
            }
        } catch (Exception e) {
            log.debug("[反馈] 查找执行 Agent 失败: {}", e.getMessage());
        }
        return null;
    }


    /** 判断节点是否为执行树根（task/inner_goal 且其 parent 不是执行节点）。 */
    private boolean isExecutionRoot(com.icusu.sivan.infra.forest.entity.ForestNodeEntity entity) {
        if (!"task".equals(entity.getNodeType()) && !"inner_goal".equals(entity.getNodeType())) {
            return false;
        }
        // 检查 parent：如果 parent 也是 task/inner_goal，则当前节点不是树根
        if (entity.getParentNodeId() != null) {
            var parent = forestNodeJpaRepository.findById(entity.getParentNodeId()).orElse(null);
            if (parent != null && ("task".equals(parent.getNodeType()) || "inner_goal".equals(parent.getNodeType()))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 记录节点级 Beta 反馈（由 node-feedback 端点调用）。
     * 通过节点内容（nodeName）查找执行树，获取 agentName 后更新 Beta 参数。
     */
    public void recordNodeBetaFeedback(UUID accountId, String nodeName, boolean success) {
        if (nodeName == null || nodeName.isBlank()) return;
        // 查找最近包含此内容的 task 节点，获取其执行树根 agentName
        var nodes = forestNodeJpaRepository.findByNodeTypeAndStatusOrderBySortOrder("task", null);
        for (var entity : nodes) {
            if (nodeName.equals(entity.getContent())) {
                // 找到匹配节点，沿 parent 链上找执行树根
                var current = entity;
                boolean found = false;
                for (int i = 0; i < 10 && current.getParentNodeId() != null; i++) {
                    var parentOpt = forestNodeJpaRepository.findById(current.getParentNodeId());
                    if (parentOpt.isEmpty()) break;
                    var parent = parentOpt.get();
                    if (!"task".equals(parent.getNodeType()) && !"inner_goal".equals(parent.getNodeType())) {
                        // parent 不是 task/inner_goal → current 就是执行树根
                        String agentName = extractAgentNameFromJson(current.getMetadata());
                        if (agentName != null) {
                            forestRoutingService.recordFeedback(accountId, agentName, nodeName, success, null);
                        }
                        found = true;
                        break;
                    }
                    current = parent;
                }
                if (!found) {
                    // 直接在当前节点 metadata 中找 agentName
                    String agentName = extractAgentNameFromJson(entity.getMetadata());
                    if (agentName != null) {
                        forestRoutingService.recordFeedback(accountId, agentName, nodeName, success, null);
                    }
                }
                break; // 只处理第一个匹配
            }
        }
    }

    /** 从 JSONB metadata 中提取 agentName。 */
    private String extractAgentNameFromJson(String json) {
        if (json == null || json.isBlank() || "{}".equals(json)) return null;
        try {
            var tree = objectMapper.readTree(json);
            var name = tree.get("agentName");
            return name != null ? name.asText() : null;
        } catch (Exception e) {
            return null;
        }
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

        Flux<String> responseStream = Mono.fromCallable(() -> doOrchestrateAndRoute(conversationId, request, conversation, accountId, userMessage.getMessageId()))
                .subscribeOn(Schedulers.boundedElastic()).flatMapMany(flux -> flux);

        if (sandboxWarnings != null && !sandboxWarnings.isEmpty()) {
            String warningJson = "{\"type\":\"error\",\"message\":\"以下文件未能复制到沙盒工作目录，文件工具可能无法访问：" + String.join("、", sandboxWarnings) + "\"}";
            responseStream = Flux.concat(Flux.just(warningJson), responseStream);
        }

        return responseStream;
    }

    private Flux<String> doOrchestrateAndRoute(UUID conversationId, SendMessageRequest request,
                                               Conversation conversation, UUID accountId, UUID userMsgId) {
        OrchPrep prep = prepareOrchestration(conversation, request.getModelProviderId(), accountId,
                userMsgId);
        return runOrchestration(conversationId, request, conversation, accountId, prep);
    }

    private record OrchPrep(Message assistantMsg, Sinks.Many<String> sink, UUID msgId,
                            int contextLength, int maxPromptTokens, int historyBudget, String modelName,
                            UUID userMsgId) {
    }

    private OrchPrep prepareOrchestration(Conversation conversation, UUID providerId, UUID accountId,
                                          UUID userMsgId) {
        int contextLength = promptContextService.resolveContextLength(providerId, accountId);
        int maxPromptTokens = (int) (contextLength * promptContextService.resolveBudgetRatio(providerId, accountId));
        int historyBudget = (int) (maxPromptTokens * 0.85);

        String modelName = resolveModelName(providerId, accountId);
        Message assistantMsg = messageRepository.save(messageCrudService.createAssistantMessage(conversation, accountId));
        conversation.incrementMessageCount();
        conversationRepository.update(conversation);

        UUID msgId = assistantMsg.getMessageId();
        Sinks.Many<String> sink = streamManager.create(msgId);
        sink.tryEmitNext(SseFormatter.buildMetaEvent(null, null, 0, 0, msgId.toString(), conversation.getConversationId().toString()));

        return new OrchPrep(assistantMsg, sink, msgId, contextLength, maxPromptTokens, historyBudget, modelName, userMsgId);
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
                    List<ToolSpec> coreTools = toolResult.tools() != null
                            ? toolResult.tools().stream()
                            .map(s -> new ToolSpec(s.name(), s.description(), s.inputSchema()))
                            .toList()
                            : null;

                    log.info("Forest 执行: toolCount={}", coreTools != null ? coreTools.size() : 0);

                    List<ToolSpec> internalTools = promptContextService.getInternalTools();
                    List<Msg> prebuiltMsgs = promptContextService.buildLlmMessages(
                            conversationId, accountId, null, null,
                            prep.contextLength, null, null, null, null, null,
                            internalTools, null, null);

                    // 注入动态上下文：用户画像、情境闪现、文件快照、项目路径提示
                    List<Msg> ctxMsgs = toolResult.contextMsgs();
                    if (ctxMsgs != null && !ctxMsgs.isEmpty()) {
                        // 在 SYSTEM 消息块（system prompt + tools）之后插入
                        int insertPos = 0;
                        while (insertPos < prebuiltMsgs.size()
                                && prebuiltMsgs.get(insertPos).role() == Role.SYSTEM) insertPos++;
                        prebuiltMsgs.addAll(insertPos, ctxMsgs);
                    }

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
                            .publishOn(Schedulers.boundedElastic())
                            .flatMap(tree -> {
                                String projectPath = groupService.getProjectDisplayPath(accountId, conversation.getProjectId());

                                // 先创建 Forest（获取 forestId），再设 metadata，再持久化
                                Forest forest = new Forest(conversationId, accountId,
                                        conversation.getProjectId(),
                                        request.getContent().length() > 50
                                                ? request.getContent().substring(0, 47) + "..."
                                                : request.getContent(),
                                        tree.nodeId());

                                forestCompressor.compress(forest, tree, "send", prep.maxPromptTokens);
                                String agent = request.getTargetAgent();
                                List<String> convKbIds = conversation.getKnowledgeBaseIds();
                                // 路由决策（forestRoutingService 内部安全处理阻塞）
                                RouteResult routeResult = forestRoutingService.resolve(accountId, request.getContent());
                                forestRoutingService.recordIfTask(accountId, conversationId, request.getContent(), routeResult);
                                // 注入运行时元数据（routeResult 传递，方法内不阻塞）
                                forestExecutionOrchestrator.injectRuntimeMetadata(tree, prebuiltMsgs, coreTools,
                                        projectPath, accountId, agent, convKbIds, conversationId, routeResult,
                                        conversation.getMcpServerIds());
                                // 持久化
                                forestExecutionOrchestrator.persist(forest, tree, accountId);
                                // 执行树根指向用户消息（而非对话容器），getMessageForest 可直接 msgId → 子树
                                if (prep.userMsgId() != null) {
                                    forestNodeJpaRepository.findById(tree.nodeId()).ifPresent(e -> {
                                        e.setParentNodeId(prep.userMsgId().toString());
                                        forestNodeJpaRepository.save(e);
                                    });
                                }
                                ExecutionContext execCtx = ExecutionContext.create(accountId,
                                        conversation.getProjectId(), conversationId);

                                StringBuilder contentAcc = new StringBuilder();
                                StringBuilder thinkingAcc = new StringBuilder();
                                long startMs = System.currentTimeMillis();

                                final int[] totalTokens = {0};
                                final int[] thinkingTokens = {0};
                                final long[] thinkingStartMs = {0};
                                final long[] thinkingDurationAcc = {0};
                                final Set<String> activePhases = new HashSet<>();
                                final int[] activeCount = {0};
                                final int[] completedCount = {0};
                                final int totalLeaves = countLeaves(tree);
                                final Map<String, String> taskContentByNodeId = buildTaskContentMap(tree);
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
                                                        totalTokens[0] += Integer.parseInt(
                                                                m.replaceAll(".*\"totalTokens\":(\\d+).*", "$1"));
                                                    } catch (Exception ignored) {
                                                    }
                                                    try {
                                                        thinkingTokens[0] += Integer.parseInt(
                                                                m.replaceAll(".*\"thinkingTokens\":(\\d+).*", "$1"));
                                                    } catch (Exception ignored) {
                                                    }
                                                }
                                                case ERROR -> {
                                                    prep.sink.tryEmitNext("{\"type\":\"error\",\"message\":\""
                                                            + escapeJson(event.message()) + "\"}");
                                                }
                                                case NODE_START -> {
                                                    String nid = event.nodeId();
                                                    activePhases.add(nid);
                                                    // 多子任务才展示标题头，单任务直接输出避免冗余
                                                    if (taskContentByNodeId.size() > 1) {
                                                        String taskContent = taskContentByNodeId.get(nid);
                                                        if (taskContent != null) {
                                                            String truncated = taskContent.length() > 50
                                                                    ? taskContent.substring(0, 50) + "…" : taskContent;
                                                            String header = "\n\n#### 📋 " + truncated + "\n\n";
                                                            contentAcc.append(header);
                                                            prep.sink.tryEmitNext(SseFormatter.toJsonEvent("response", header));
                                                        }
                                                    }
                                                    if (++activeCount[0] == 1) {
                                                        prep.sink.tryEmitNext(SseFormatter.buildPhaseStartEvent(
                                                                "执行", null, null, 0, totalLeaves));
                                                    }
                                                }
                                                case NODE_END -> {
                                                    String nid = event.nodeId();
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
                                            String msgContent = contentAcc.toString();
                                            // LLM 可能将全部产出放入 thinking 而非 content，降级回退
                                            if (msgContent.isEmpty() && thinkingContent != null) {
                                                msgContent = thinkingContent;
                                                thinkingContent = null;
                                            }
                                            prep.assistantMsg.setContent(msgContent);
                                            prep.assistantMsg.setThinking(thinkingContent);
                                            prep.assistantMsg.setStatus(
                                                    signal == SignalType.ON_COMPLETE
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
                                            forestExecutionOrchestrator.linkMessage(prep.assistantMsg.getMessageId(), tree.nodeId());
                                            log.debug("[持久化] 助理消息已保存: msgId={}, status={}, contentLen={} thinkLen={}",
                                                    prep.msgId, prep.assistantMsg.getStatus(), contentAcc.length(),
                                                    thinkingAcc.length());
                                            boolean success = signal == SignalType.ON_COMPLETE;
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
                                                    prep.msgId.toString(), conversationId.toString()));
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
        final java.util.UUID capturedUserMsgId = userMsg != null ? userMsg.getMessageId() : null;
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
                        List<ToolSpec> regInternalTools = promptContextService.getInternalTools();
                        List<Msg> prebuiltMsgs = promptContextService.buildLlmMessages(conversationId, accountId,
                                regenerateImages, regenerateAudios, contextLength,
                                aiMsg.getMessageId(), null, null, null, providerId,
                                regInternalTools, null, null);
                        PromptContextService.insertContextMessages(prebuiltMsgs, epochResult, enhancedContext);

                        // 注入项目路径提示，让 LLM 知道当前工作目录
                        String projHint = promptContextService.buildProjectHint(conversation, accountId);
                        if (projHint != null) {
                            prebuiltMsgs.add(1, Msg.of(Role.USER, projHint));
                        }

                        TaskNode tree = new TaskNode(userContent);
                        String projectPath = groupService.getProjectDisplayPath(accountId, conversation.getProjectId());
                        Forest forest = new Forest(conversationId, accountId,
                                conversation.getProjectId(),
                                userContent.length() > 50 ? userContent.substring(0, 47) + "..." : userContent,
                                tree.nodeId());
                        forestCompressor.compress(forest, tree, "send", contextLength);
                        List<String> convKbIds = conversation.getKnowledgeBaseIds();
                        // 注入运行时元数据（routeResult=null，不触发路由决策）
                        forestExecutionOrchestrator.injectRuntimeMetadata(tree, prebuiltMsgs, null,
                                projectPath, accountId, null, convKbIds, conversationId, null,
                                conversation.getMcpServerIds());
                        forestExecutionOrchestrator.persist(forest, tree, accountId);
                        // 执行树根指向用户消息
                        if (capturedUserMsgId != null) {
                            forestNodeJpaRepository.findById(tree.nodeId()).ifPresent(e -> {
                                e.setParentNodeId(capturedUserMsgId.toString());
                                forestNodeJpaRepository.save(e);
                            });
                        }
                        ExecutionContext execCtx = ExecutionContext.create(accountId,
                                conversation.getProjectId(), conversationId);

                        StringBuilder contentAcc = new StringBuilder();
                        StringBuilder thinkingAcc = new StringBuilder();
                        long startMs = System.currentTimeMillis();
                        final int[] totalTokens = {0};
                        final int[] thinkingTokens = {0};
                        final long[] thinkingStartMs = {0};
                        final long[] thinkingDurationAcc = {0};
                        final Set<String> regActivePhases = new HashSet<>();
                        final int[] regActiveCount = {0};
                        final int[] regCompletedCount = {0};
                        final int regTotalLeaves = countLeaves(tree);
                        final Map<String, String> regTaskContentByNodeId = buildTaskContentMap(tree);
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
                                        case NODE_START -> {
                                            String nid = event.nodeId();
                                            regActivePhases.add(nid);
                                            if (regTaskContentByNodeId.size() > 1) {
                                                String taskContent = regTaskContentByNodeId.get(nid);
                                                if (taskContent != null) {
                                                    String truncated = taskContent.length() > 50
                                                            ? taskContent.substring(0, 50) + "…" : taskContent;
                                                    String header = "\n\n#### 📋 " + truncated + "\n\n";
                                                    contentAcc.append(header);
                                                    sink.tryEmitNext(SseFormatter.toJsonEvent("response", header));
                                                }
                                            }
                                            if (++regActiveCount[0] == 1) {
                                                sink.tryEmitNext(SseFormatter.buildPhaseStartEvent(
                                                        "执行", null, null, 0, regTotalLeaves));
                                            }
                                        }
                                        case NODE_END -> {
                                            String nid = event.nodeId();
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
                                })
                                .publishOn(Schedulers.boundedElastic())
                                .doFinally(signal -> {
                                    int durationMs = (int) (System.currentTimeMillis() - startMs);
                                    int thinkingDurationMs = (int) (thinkingDurationAcc[0]
                                            + (thinkingStartMs[0] > 0
                                            ? System.currentTimeMillis() - thinkingStartMs[0] : 0));
                                    String thinkingContent = thinkingAcc.length() > 0 ? thinkingAcc.toString() : null;
                                    String msgContent = contentAcc.toString();
                                    // LLM 可能将全部产出放入 thinking 而非 content，降级回退
                                    if (msgContent.isEmpty() && thinkingContent != null) {
                                        msgContent = thinkingContent;
                                        thinkingContent = null;
                                    }
                                    finalAssistantMsg.setContent(msgContent);
                                    finalAssistantMsg.setThinking(thinkingContent);
                                    finalAssistantMsg.setStatus(
                                            signal == SignalType.ON_COMPLETE
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
                                    forestExecutionOrchestrator.linkMessage(finalAssistantMsg.getMessageId(), tree.nodeId());
                                    log.debug("[持久化] 重新生成消息已保存: msgId={}, status={}, contentLen={} thinkLen={}",
                                            msgId, finalAssistantMsg.getStatus(), contentAcc.length(),
                                            thinkingAcc.length());
                                    metricsCollector.recordExecution(totalTokens[0], durationMs);
                                    eventPublisher.publishEvent(new MessageCompletedEvent(
                                            msgId, conversationId, accountId,
                                            conversation.getProjectId(), contentAcc.toString(), thinkingContent,
                                            modelName, totalTokens[0], durationMs, thinkingDurationMs));
                                    UUID genGroup = finalAssistantMsg.getGenerationGroup();
                                    String genGroupStr = genGroup != null ? genGroup.toString() : null;
                                    Integer genTotal = genGroup != null
                                            ? messageRepository.countByGenerationGroup(genGroup) : null;
                                    sink.tryEmitNext(SseFormatter.buildMetaEvent(
                                            modelName, totalTokens[0] > 0 ? totalTokens[0] : null,
                                            durationMs, thinkingDurationMs,
                                            thinkingTokens[0] > 0 ? thinkingTokens[0] : null,
                                            msgId.toString(), conversationId.toString(),
                                            genGroupStr, genTotal));
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
                            message.getConversationId().toString()));
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
            String chain = msg.getConversationId().toString();
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
            return Collections.emptyMap();
        }
        Message msg = opt.get();
        String progressJson = msg.getProgress();
        if (progressJson == null || progressJson.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(progressJson, Map.class);
        } catch (Exception e) {
            log.warn("解析 progress JSON 失败: msgId={}", messageId, e);
            return Collections.emptyMap();
        }
    }

    /**
     * 获取消息的执行树。
     * 执行树根是消息的子节点（parent_node_id = messageId），直接加载。
     */
    public ForestTreeResponse getMessageForest(UUID accountId, UUID conversationId, UUID messageId) {
        conversationCrudService.findOwned(accountId, conversationId);

        // 双向查找执行树根：
        // 1) 助手消息 → 其父节点就是执行树根
        // 2) 用户消息 → 其子节点中包含执行树根
        String execRootId = null;
        var msgEntity = forestNodeJpaRepository.findById(messageId.toString()).orElse(null);
        if (msgEntity != null) {
            String parentId = msgEntity.getParentNodeId();
            if (parentId != null) {
                // 检查父节点是否为执行树根（助手消息 case）
                var parent = forestNodeJpaRepository.findById(parentId).orElse(null);
                if (parent != null && ("task".equals(parent.getNodeType()) || "inner_goal".equals(parent.getNodeType()))) {
                    execRootId = parentId;
                }
            }
            if (execRootId == null) {
                // 检查子节点中是否有执行树根（用户消息 case）
                var children = forestNodeJpaRepository.findByForestIdAndParentNodeIdOrderBySortOrderAsc(
                        conversationId, messageId.toString());
                var childRoot = children.stream()
                        .filter(e -> "task".equals(e.getNodeType()) || "inner_goal".equals(e.getNodeType()))
                        .findFirst().orElse(null);
                if (childRoot != null) execRootId = childRoot.getNodeId();
            }
        }
        TreeNode root = null;
        if (execRootId != null) {
            root = forestRepository.findSubtree(execRootId, accountId);
        }
        // 第三回退：查找对话下第一个 RUNNING 或 COMPLETED 的执行树
        if (root == null) {
            var running = forestNodeJpaRepository.findAllRootNodesByStatus(NodeStatus.RUNNING.name()).stream()
                    .filter(e -> e.getForestId().equals(conversationId))
                    .findFirst().orElse(null);
            if (running != null) {
                execRootId = running.getNodeId();
                root = forestRepository.findSubtree(execRootId, accountId);
            }
        }
        if (root == null) {
            log.debug("无执行树记录: conversationId={} messageId={}", conversationId, messageId);
            return null;
        }

        // 加载工具调用日志（预加载所有日志，避免 N+1）
        UUID forestUuid = conversationId;
        java.util.Map<String, java.util.List<ForestExecutionLogEntity>> toolLogsByNode = new java.util.HashMap<>();
        if (executionLogRepo != null) {
            try {
                var allToolCalls = executionLogRepo.findToolCallsByForest(forestUuid);
                if (allToolCalls != null) {
                    for (var log : allToolCalls) {
                        toolLogsByNode.computeIfAbsent(log.getNodeId(), k -> new java.util.ArrayList<>()).add(log);
                    }
                }
            } catch (Exception e) {
                log.debug("加载工具调用日志失败: {}", e.getMessage());
            }
        }

        // 构建进度
        int total = countForestLeaves(root);
        int completed = countCompletedLeaves(root);

        ForestTreeResponse resp = new ForestTreeResponse();
        resp.setForestId(forestUuid.toString());
        resp.setRoot(toForestTreeNode(root, toolLogsByNode));
        resp.setProgress(new ForestTreeResponse.Progress(completed, total));
        return resp;
    }

    private ForestTreeResponse.TreeNode toForestTreeNode(TreeNode node,
                                                          java.util.Map<String, java.util.List<ForestExecutionLogEntity>> toolLogsByNode) {
        ForestTreeResponse.TreeNode tn = new ForestTreeResponse.TreeNode();
        tn.setNodeId(node.nodeId());
        // name = 节点描述（任务内容），output = 执行产出（metadata.output）
        String nodeOutput = null;
        if (node instanceof ContentNode cn) {
            nodeOutput = cn.metadataString("output");
            String taskContent = cn.content();
            tn.setName(taskContent != null && !taskContent.isBlank() ? taskContent : node.nodeType());
            tn.setOutput(nodeOutput);
        } else {
            nodeOutput = node.metadataString("output");
            String reasoning = node.metadataString("reasoning");
            tn.setName(reasoning != null && !reasoning.isBlank() ? reasoning : node.nodeType());
            tn.setOutput(nodeOutput);
        }
        tn.setLeaf(node.isLeaf());
        if (node instanceof ExecutableNode en) {
            tn.setStatus(en.status().name());
            tn.setMode(en.mode() != Mode.NONE ? en.mode().name() : null);
        } else {
            tn.setStatus("PENDING");
        }
        if (node instanceof ContentNode cn) {
            Object agentName = cn.metadata().get("agentName");
            if (agentName instanceof String s) tn.setAgent(s);
            Object routeTier = cn.metadata().get("_routeTier");
            if (routeTier instanceof Integer i) tn.setRouteTier(i);
            Object routeConf = cn.metadata().get("_routeConfidence");
            if (routeConf instanceof Double d) tn.setRouteConfidence(d);
        }
        // reasoning 用于 display
        Object reasoning = node.metadata().get("reasoning");
        if (reasoning instanceof String rs && !rs.isBlank()) {
            tn.setReasoning(rs);
        }
        // output（节点执行产出）
        Object output = node.metadata().get("output");
        if (output instanceof String os && !os.isBlank()) {
            tn.setOutput(os);
        }
        // durationMs & tokens（实际执行耗时和 token 消耗）
        Object durationMs = node.metadata().get("durationMs");
        if (durationMs instanceof Integer dms) {
            tn.setDurationMs(dms);
        } else if (durationMs instanceof Number n) {
            tn.setDurationMs(n.intValue());
        }
        if (node instanceof com.icusu.sivan.domain.forest.tree.CompressibleNode comp) {
            Object actualTokens = node.metadata().get("_actualTokens");
            if (actualTokens instanceof Number n && n.intValue() > 0) {
                tn.setTokens(n.intValue());
            } else {
                long est = comp.estimateSubtreeTokens();
                if (est > 0) tn.setTokens((int) Math.min(est, Integer.MAX_VALUE));
            }
        }
        // 加载工具调用摘要
        String nid = node.nodeId();
        if (toolLogsByNode != null && toolLogsByNode.containsKey(nid)) {
            var logs = toolLogsByNode.get(nid);
            java.util.Map<String, Integer> toolCount = new java.util.LinkedHashMap<>();
            for (var log : logs) {
                String msg = log.getMessage();
                String toolName = "unknown";
                if (msg != null && msg.startsWith("{")) {
                    try {
                        var parsed = objectMapper.readTree(msg);
                        var n = parsed.get("name");
                        if (n != null) toolName = n.asText();
                    } catch (Exception ignored) {}
                } else if (msg != null) {
                    toolName = msg;
                }
                toolCount.merge(toolName, 1, Integer::sum);
            }
            tn.setToolCalls(toolCount.entrySet().stream()
                    .map(e -> new ForestTreeResponse.ToolCall(e.getKey(), e.getValue(), "ok"))
                    .toList());
        }
        // 加载 A2A 消息
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
                    .filter(c -> c instanceof ExecutableNode)
                    .map(c -> toForestTreeNode(c, toolLogsByNode))
                    .toList());
        }
        return tn;
    }

    private static int countForestLeaves(TreeNode node) {
        if (node.isLeaf()) return 1;
        return node.children().stream()
                .filter(c -> c instanceof ExecutableNode)
                .mapToInt(ForestConversationService::countForestLeaves)
                .sum();
    }

    private static int countCompletedLeaves(TreeNode node) {
        if (node.isLeaf()) {
            return node instanceof ExecutableNode en
                    && en.status() == NodeStatus.COMPLETED ? 1 : 0;
        }
        return node.children().stream()
                .filter(c -> c instanceof ExecutableNode)
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
            if (treeMatcher instanceof LlmTreeMatcher llm) {
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

    /**
     * 遍历树，构建 nodeId → taskContent 映射（仅叶子 TaskNode）。 */
    private static Map<String, String> buildTaskContentMap(TreeNode root) {
        Map<String, String> map = new HashMap<>();
        collectTaskContent(root, map);
        return map;
    }

    private static void collectTaskContent(TreeNode node, Map<String, String> map) {
        if (node instanceof ContentNode cn) {
            String content = cn.content();
            if (content != null && !content.isBlank() && node.isLeaf()) {
                map.put(node.nodeId(), content);
            }
        }
        for (TreeNode child : node.children()) {
            collectTaskContent(child, map);
        }
    }

    /**
     * 订阅 Flashback SSE 流，委托给 StreamManager。
     */
    public Flux<String> subscribeFlashback() {
        return streamManager.subscribeFlashback();
    }
}
