package com.icusu.sivan.web.conversation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.icusu.sivan.agent.mcp.McpConnectionManager;
import com.icusu.sivan.agent.model.ModelCapabilityRegistry;
import com.icusu.sivan.agent.model.ModelRouter;
import com.icusu.sivan.agent.prompt.ChatPrompts;
import com.icusu.sivan.agent.routing.RoutingDecisionRecorder;
import com.icusu.sivan.agent.tool.MatchedTools;
import com.icusu.sivan.agent.tool.ToolEnricher;
import com.icusu.sivan.agent.tool.ToolRegistryImpl;
import com.icusu.sivan.infra.shared.sse.SseSanitizer;
import com.icusu.sivan.agent.tool.ToolResolver;
import com.icusu.sivan.memory.flashback.FlashbackScanner;
import com.icusu.sivan.common.context.Account;
import com.icusu.sivan.common.enums.Intent;
import com.icusu.sivan.common.enums.MessageStatus;
import com.icusu.sivan.common.exception.DomainException;
import com.icusu.sivan.common.exception.ResourceNotFoundException;
import com.icusu.sivan.common.util.JsonUtil;
import com.icusu.sivan.common.util.OwnershipValidator;
import com.icusu.sivan.core.message.Msg;
import com.icusu.sivan.core.message.Role;
import com.icusu.sivan.core.tool.ToolSpec;
import com.icusu.sivan.domain.account.IAccountRepository;
import com.icusu.sivan.domain.account.IUserProfileRepository;
import com.icusu.sivan.domain.account.UserProfile;
import com.icusu.sivan.domain.agent.ISkillRepository;
import com.icusu.sivan.domain.context.ContextSegment;
import com.icusu.sivan.domain.conversation.*;
import com.icusu.sivan.domain.file.FileStoragePort;
import com.icusu.sivan.domain.goal.Goal;
import com.icusu.sivan.domain.goal.IGoalRepository;
import com.icusu.sivan.domain.goal.Milestone;
import com.icusu.sivan.domain.goal.Task;
import com.icusu.sivan.domain.model.LlmProvider;
import com.icusu.sivan.domain.model.ModelCapability;
import com.icusu.sivan.domain.pipeline.IPipelineStepRepository;
import com.icusu.sivan.domain.pipeline.PipelineStep;
import com.icusu.sivan.domain.shared.event.MessageCompletedEvent;
import com.icusu.sivan.domain.shared.port.IEmbeddingService;
import com.icusu.sivan.domain.shared.util.CosineSimilarity;
import com.icusu.sivan.domain.tool.IToolMatchLogRepository;
import com.icusu.sivan.domain.tool.ToolMatchLog;
import com.icusu.sivan.infra.file.DocumentTextExtractor;
import com.icusu.sivan.infra.shared.sse.SseFormatter;
import com.icusu.sivan.infra.shared.sse.StreamManager;
import com.icusu.sivan.orch.executor.OrchestrationEvent;
import com.icusu.sivan.orch.executor.SquadOrchestrator;
import com.icusu.sivan.web.agent.service.GroupService;
import com.icusu.sivan.web.conversation.dto.*;
import com.icusu.sivan.web.conversation.service.compress.ConversationCompressor;
import com.icusu.sivan.web.conversation.service.message.*;
import com.icusu.sivan.web.conversation.service.tree.ContextBuilder;
import com.icusu.sivan.web.conversation.service.tree.ContextCache;
import com.icusu.sivan.web.conversation.service.tree.ContextResult;

import com.icusu.sivan.web.knowledge.service.RagContextBuilder;
import com.icusu.sivan.web.orchestration.dto.PipelineStepResponse;
import com.icusu.sivan.web.routing.service.StrategyPerformanceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 对话服务，管理对话、消息收发、流式 LLM 调用与工具执行。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationService {

    private final IConversationRepository conversationRepository;
    private final IMessageRepository messageRepository;
    private final StreamManager streamManager;
    private final ApplicationEventPublisher eventPublisher;
    private final ModelRouter modelRouter;
    private final FileStoragePort fileStorageService;
    private final RagContextBuilder ragContextBuilder;
    private final SquadOrchestrator squadOrchestrator;
    private final HistoryCompressor historyCompressor;
    private final RoutingDecisionRecorder routingDecisionRecorder;
    private final StreamingMessageEngine streamingEngine;
    private final ToolResolver toolAutoResolver;
    private final ContextBuilder contextBuilder;
    private final ContextCache contextCache;
    private final IPipelineStepRepository pipelineStepRepository;
    private final StrategyPerformanceService performanceService;
    private final IAccountRepository accountRepository;
    private final McpConnectionManager mcpConnectionManager;
    private final ToolEnricher toolEnricher;
    private final IToolMatchLogRepository toolMatchLogRepository;
    private final ISkillRepository skillRepository;
    private final ToolRegistryImpl toolRegistry;
    private final GroupService groupService;
    private final FlashbackScanner flashbackScanner;
    private final ModelCapabilityRegistry modelCapabilityRegistry;
    private final IUserProfileRepository userProfileRepository;
    private final DocumentTextExtractor documentTextExtractor;
    private final IEmbeddingService embeddingService;
    private final IGoalRepository goalRepository;

    @Value("${sivan.file.root-path}")
    private String fileRootPath;
    private final ConversationCompressor conversationCompressor;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final int MAX_MESSAGES_LOAD = 500;
    private static final int DEFAULT_PAGE_LIMIT = 50;
    private static final long FLUSH_INTERVAL_MS = 2000;

    /**
     * 首 token 到达时间戳追踪（msgId → continueOrchestration 起始 ms）。
     */
    private final ConcurrentHashMap<UUID, Long> firstChunkRef = new ConcurrentHashMap<>();

    /**
     * 异步压缩 Future 追踪（conversationId → CompletableFuture），
     * 用于 resolveCompressResult 等待进行中的异步压缩完成，避免使用过期快照。
     */
    private final ConcurrentHashMap<UUID, CompletableFuture<String>> compressionFutures = new ConcurrentHashMap<>();

    /**
     * 构建用户画像段落（不含 CHAT_SYSTEM 前缀），用于独立 USER 消息注入。
     * 优先从 UserProfile 实体读取，降级到 preferences JSON。
     */
    private String buildUserProfileSection(UUID accountId) {
        try {
            var profileOpt = userProfileRepository.findByAccountId(accountId);
            if (profileOpt.isPresent()) {
                return formatProfileSection(profileOpt.get(), profileOpt.get().getExpertise());
            }
            var accountOpt = accountRepository.findById(accountId);
            if (accountOpt.isPresent() && accountOpt.get().getPreferences() != null) {
                return formatProfileFromPreferences(accountOpt.get().getPreferences());
            }
        } catch (Exception e) {
            log.debug("构建用户画像失败: {}", e.getMessage());
        }
        return null;
    }

    /** 从 UserProfile 格式化为文本段落（可指定 topTags 做语义过滤）。 */
    private static String formatProfileSection(UserProfile profile, List<String> topTags) {
        StringBuilder p = new StringBuilder("## 用户信息\n");
        boolean hasProfile = false;
        if (profile.getName() != null && !profile.getName().isBlank()) {
            p.append("- 称呼：").append(profile.getName()).append("\n");
            hasProfile = true;
        }
        if (profile.getBio() != null && !profile.getBio().isBlank()) {
            p.append("- 简介：").append(profile.getBio()).append("\n");
            hasProfile = true;
        }
        if (profile.getAiLanguage() != null && !profile.getAiLanguage().isBlank()
                && !"auto".equals(profile.getAiLanguage())) {
            p.append("- 回复语言：").append(profile.getAiLanguage()).append("\n");
            hasProfile = true;
        }
        List<String> tags = topTags != null ? topTags : profile.getExpertise();
        if (tags != null && !tags.isEmpty()) {
            p.append("- 技术栈：").append(String.join("、", tags)).append("\n");
            hasProfile = true;
        }
        return hasProfile ? p.toString() : null;
    }

    /** 从 Account.preferences JSON 格式化为文本段落。 */
    private String formatProfileFromPreferences(String preferencesJson) {
        try {
            var prefs = objectMapper.readTree(preferencesJson);
            StringBuilder p = new StringBuilder("## 用户信息\n");
            boolean hasProfile = false;
            if (prefs.has("name") && !prefs.get("name").asText().isBlank()) {
                p.append("- 称呼：").append(prefs.get("name").asText()).append("\n");
                hasProfile = true;
            }
            if (prefs.has("bio") && !prefs.get("bio").asText().isBlank()) {
                p.append("- 简介：").append(prefs.get("bio").asText()).append("\n");
                hasProfile = true;
            }
            if (prefs.has("aiLanguage") && !prefs.get("aiLanguage").asText().isBlank()) {
                p.append("- 回复语言：").append(prefs.get("aiLanguage").asText()).append("\n");
                hasProfile = true;
            }
            if (prefs.has("expertise") && prefs.get("expertise").isArray()) {
                var arr = prefs.get("expertise");
                var items = new java.util.ArrayList<String>();
                for (int i = 0; i < arr.size(); i++) items.add(arr.get(i).asText());
                p.append("- 技术栈：").append(String.join("、", items)).append("\n");
                hasProfile = true;
            }
            return hasProfile ? p.toString() : null;
        } catch (Exception e) {
            log.debug("解析 preferences JSON 失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 构建带语义过滤的用户画像段落。根据当前查询与画像 expertise 的语义相关性，
     * 仅保留 top-3 最相关的标签，减少 prompt 噪声。
     */
    private String buildUserProfileSection(UUID accountId, String queryContext) {
        if (queryContext == null || queryContext.isBlank() || !embeddingService.isAvailable()) {
            return buildUserProfileSection(accountId);
        }
        try {
            var profileOpt = userProfileRepository.findByAccountId(accountId);
            if (profileOpt.isEmpty()) return buildUserProfileSection(accountId);

            UserProfile profile = profileOpt.get();
            if (profile.getExpertise() == null || profile.getExpertise().isEmpty()) {
                return buildUserProfileSection(accountId);
            }

            float[] queryVec = embeddingService.embed(queryContext);
            if (queryVec == null) return buildUserProfileSection(accountId);

            var scored = new java.util.ArrayList<ScoredTag>();
            for (String tag : profile.getExpertise()) {
                float[] tagVec = embeddingService.embed(tag);
                if (tagVec != null) {
                    double sim = CosineSimilarity.compute(queryVec, tagVec);
                    scored.add(new ScoredTag(tag, sim));
                }
            }
            scored.sort((a, b) -> Double.compare(b.score, a.score));

            var topTags = scored.stream()
                    .limit(3)
                    .map(s -> s.tag)
                    .toList();
            return formatProfileSection(profile, topTags);
        } catch (Exception e) {
            log.debug("语义过滤画像失败，使用完整画像: {}", e.getMessage());
            return buildUserProfileSection(accountId);
        }
    }

    /** 带分值的标签对。 */
    private record ScoredTag(String tag, double score) {}

    /**
     * 创建新对话。
     */
    public ConversationResponse create(UUID accountId, CreateConversationRequest request) {
        UUID projectId = request.getProjectId();
        if (projectId == null) {
            throw new DomainException("请先选择或创建一个项目");
        }
        var project = groupService.findOwned(accountId, projectId);
        if (Boolean.TRUE.equals(project.getArchived())) {
            throw new DomainException("项目已归档，无法创建新对话");
        }
        Conversation conversation = Conversation.builder().accountId(accountId).projectId(projectId).title(request.getTitle() != null ? request.getTitle() : "新对话").messageCount(0).build();

        conversationRepository.save(conversation);
        return toResponse(conversation);
    }

    /**
     * 根据 ID 查询对话。
     */
    public ConversationResponse getById(UUID accountId, UUID conversationId) {
        return toResponse(findOwned(accountId, conversationId));
    }

    /**
     * 查询对话列表。
     */
    public List<ConversationResponse> list(UUID accountId, UUID projectId) {
        List<Conversation> conversations = projectId != null ? conversationRepository.findAllByAccountAndProject(accountId, projectId) : conversationRepository.findAllByAccount(accountId);
        return conversations.stream().map(this::toResponse).toList();
    }

    /**
     * 更新对话（标题、项目、知识库绑定）。
     */
    public ConversationResponse update(UUID accountId, UUID conversationId, UpdateConversationRequest request) {
        Conversation conversation = findOwned(accountId, conversationId);
        conversation.updateFrom(request.getTitle(), request.getProjectId(), request.getKnowledgeBaseIds());
        conversationRepository.update(conversation);
        return toResponse(conversation);
    }

    /**
     * 删除对话及关联消息。
     */
    @Transactional
    public void delete(UUID accountId, UUID conversationId) {
        Conversation conversation = findOwned(accountId, conversationId);
        messageRepository.deleteByConversationId(conversation.getConversationId());
        conversationRepository.delete(conversation.getConversationId());
    }

    /**
     * 发送用户消息（非流式）。
     */
    @Transactional
    public MessageResponse sendMessage(UUID accountId, UUID conversationId, SendMessageRequest request) {
        Conversation conversation = findOwned(accountId, conversationId);

        Message message = buildUserMessage(conversationId, accountId, conversation, request);

        message = messageRepository.save(message);

        conversation.incrementMessageCount();
        conversationRepository.update(conversation);

        return toMessageResponse(message);
    }

    /**
     * 发送消息并流式返回 LLM 回复。
     * LLM 调用在后台独立线程运行 (STARTED)，与 SSE 订阅解耦。
     * 客户端断开 SSE 不影响 LLM 继续生成，可重新订阅续接。
     */
    @Transactional
    public Flux<String> streamMessage(UUID accountId, UUID conversationId, SendMessageRequest request) {
        Conversation conversation = findOwned(accountId, conversationId);

        UUID providerId = request.getModelProviderId();


        // 先保存用户消息（含图片），确保前端能展示
        Message userMessage = buildUserMessage(conversationId, accountId, conversation, request);
        userMessage = messageRepository.save(userMessage);

        // 保存 MCP 工具选择到对话（不论请求是否携带工具，始终更新以支持清空）
        List<String> serverIds = request.getMcpServerIds() != null
                ? request.getMcpServerIds().stream().map(UUID::toString).toList()
                : List.of();
        conversation.setMcpServerIds(serverIds.isEmpty() ? null : serverIds);
        conversationRepository.update(conversation);

        // 上传文件复制到沙盒工作目录，确保 file_read 等工具可访问
        List<String> sandboxWarnings = copyAttachmentsToSandbox(accountId, conversation, request);

        // 图片检测：模型不支持视觉时仅不送入 LLM，用户消息仍保留图片展示
        List<String> llmImages = request.getImages();
        if (llmImages != null && !llmImages.isEmpty()) {
            boolean visionSupported;
            if (providerId != null) {
                var provider = modelRouter.getProvider(providerId);
                visionSupported = provider != null && provider.supportsCapability("vision");
            } else {
                visionSupported = modelRouter.getDefaultProvider(accountId).supportsCapability("vision");
            }
            if (!visionSupported) {
                llmImages = null;
            }
        }

        // 音频检测：模型不支持音频时仅不送入 LLM
        List<String> llmAudios = request.getAudios();
        if (llmAudios != null && !llmAudios.isEmpty()) {
            boolean audioSupported;
            if (providerId != null) {
                var provider = modelRouter.getProvider(providerId);
                audioSupported = provider != null && provider.supportsCapability("audio");
            } else {
                audioSupported = modelRouter.getDefaultProvider(accountId).supportsCapability("audio");
            }
            if (!audioSupported) {
                llmAudios = null;
            }
        }

        Flux<String> responseStream = decideStreamPath(conversationId, request, conversation, providerId, llmImages, llmAudios, accountId, userMessage.getMessageId(), request.isStream());

        if (sandboxWarnings != null && !sandboxWarnings.isEmpty()) {
            String warningJson = "{\"type\":\"error\",\"message\":\"以下文件未能复制到沙盒工作目录，文件工具可能无法访问："
                    + String.join("、", sandboxWarnings) + "\"}";
            responseStream = Flux.concat(Flux.just(warningJson), responseStream);
        }

        return responseStream;
    }

    /**
     * 在 bounded elastic 线程上执行编排决策，避免 blockLast() 在 Netty 事件循环线程上引发异常。
     * accountId 已作为参数透传，无需 ThreadLocal 桥接。
     */
    private Flux<String> decideStreamPath(UUID conversationId, SendMessageRequest request, Conversation conversation, UUID providerId, List<String> llmImages, List<String> llmAudios, UUID accountId, UUID userMessageId, boolean stream) {
        return Mono.fromCallable(() -> doOrchestrateAndRoute(conversationId, request, conversation, providerId, llmImages, llmAudios, accountId, userMessageId, stream)).subscribeOn(Schedulers.boundedElastic()).flatMapMany(flux -> flux);
    }

    private Flux<String> doOrchestrateAndRoute(UUID conversationId, SendMessageRequest request, Conversation conversation, UUID providerId, List<String> llmImages, List<String> llmAudios, UUID accountId, UUID userMessageId, boolean stream) {
        // Step 1: 判断意图来源：前端显式指令 > LLM 分类
        String forceIntent = request.getForceIntent();
        if ("SQUAD".equalsIgnoreCase(forceIntent) || "SINGLE_AGENT".equalsIgnoreCase(forceIntent)) {
            Intent intent = Intent.valueOf(forceIntent.toUpperCase());
            UUID routingDecisionId = recordRouting(accountId, conversation.getProjectId(), conversationId, request.getContent(), "FORCE_INTENT", intent.name(), true, "前端强制意图: " + forceIntent, null);
            return handleOrchestrationPath(conversationId, request, conversation, providerId, accountId, intent, routingDecisionId, llmImages, llmAudios, stream);
        }

        // Step 2: 提前准备 SSE sink，流式执行 LLM classify，token 推送到前端
        OrchPrep prep = prepareOrchestration(conversation, providerId, accountId, null);
        String assistantContext = getLastAssistantContent(conversationId);
        return squadOrchestrator.resolveIntent(request.getContent(), accountId,
                        conversation.getProjectId(), conversationId,
                        token -> prep.sink.tryEmitNext(SseFormatter.toJsonEvent("classify", token)),
                        assistantContext)
                .flatMapMany(intent -> {
                    String agentName = intent == Intent.CHAT ? null : intent.name();
                    UUID routingDecisionId = recordRouting(accountId, conversation.getProjectId(), conversationId,
                            request.getContent(), "INTENT_CLASSIFICATION", agentName, true, "意图分类结果", null);
                    // 路由决策后立即发射 chain，前端可提前展示路由决策气泡
                    prep.sink.tryEmitNext(SseFormatter.buildMetaEvent(null, null, 0, 0, prep.msgId.toString(), routingDecisionId.toString()));
                    return continueOrchestration(conversationId, request, conversation, providerId, accountId, intent, routingDecisionId, llmImages, llmAudios, stream, prep);
                })
                .onErrorResume(e -> {
                    log.warn("意图分类失败，降级为 CHAT: {}", e.getMessage());
                    UUID routingDecisionId = recordRouting(accountId, conversation.getProjectId(), conversationId,
                            request.getContent(), "INTENT_CLASSIFICATION", null, true, "意图分类失败降级", null);
                    prep.sink.tryEmitNext(SseFormatter.buildMetaEvent(null, null, 0, 0, prep.msgId.toString(), routingDecisionId.toString()));
                    return continueOrchestration(conversationId, request, conversation, providerId, accountId, Intent.CHAT, routingDecisionId, llmImages, llmAudios, stream, prep);
                });
    }

    /**
     * 编排预准备：创建助理消息、SSE sink，计算 token 预算。
     */
    private record OrchPrep(Message assistantMsg, Sinks.Many<String> sink, UUID msgId,
                            int contextLength, int maxPromptTokens, int historyBudget) {
    }

    private OrchPrep prepareOrchestration(Conversation conversation, UUID providerId, UUID accountId, UUID routingDecisionId) {
        int contextLength = resolveContextLength(providerId, accountId);
        int maxPromptTokens = (int) (contextLength * resolveBudgetRatio(providerId, accountId));
        int historyBudget = (int) (maxPromptTokens * 0.85);

        Message assistantMsg = messageRepository.save(createAssistantMessage(conversation, accountId));
        conversation.incrementMessageCount();
        conversationRepository.update(conversation);

        UUID msgId = assistantMsg.getMessageId();
        Sinks.Many<String> sink = streamManager.create(msgId);
        sink.tryEmitNext(SseFormatter.buildMetaEvent(null, null, 0, 0, msgId.toString(),
                routingDecisionId != null ? routingDecisionId.toString() : null));

        return new OrchPrep(assistantMsg, sink, msgId, contextLength, maxPromptTokens, historyBudget);
    }

    /**
     * 统一编排流路径：历史压缩 → 上下文增强 → 编排 → SSE 推送 → 持久化。
     * CHAT / SINGLE_AGENT / SQUAD 三模式共享同一事件协议。
     */
    private Flux<String> handleOrchestrationPath(UUID conversationId, SendMessageRequest request, Conversation conversation, UUID providerId, UUID accountId, Intent intent, UUID routingDecisionId, List<String> llmImages, List<String> llmAudios, boolean stream) {
        OrchPrep prep = prepareOrchestration(conversation, providerId, accountId, routingDecisionId);
        return continueOrchestration(conversationId, request, conversation, providerId, accountId, intent, routingDecisionId, llmImages, llmAudios, stream, prep);
    }

    /**
     * 编排流继续：从预准备状态（OrchPrep）开始执行压缩 → 上下文增强 → 编排 → SSE 推送 → 持久化。
     */
    private Flux<String> continueOrchestration(UUID conversationId, SendMessageRequest request,
                                               Conversation conversation, UUID providerId, UUID accountId,
                                               Intent intent, UUID routingDecisionId,
                                               List<String> llmImages, List<String> llmAudios,
                                               boolean stream, OrchPrep prep) {
        var account = new Account(accountId, conversation.getProjectId());

        log.info("编排流启动: conversationId={}, msgId={}, intent={}, providerId={}, contextLength={}",
                conversationId, prep.msgId, intent, providerId, prep.contextLength);

        Consumer<String> progress = msg -> log.debug("编排进度: {}", msg);

        ConcurrentLinkedDeque<PipelineStep> pipelineSteps = new ConcurrentLinkedDeque<>();

        // 预加载消息列表一次，后续压缩/上下文构建共享同一份数据，避免重复查询
        List<Message> allMessages = messageRepository.findByConversationId(conversationId);

        // 延迟压缩：优先读快照，无快照时使用结构感知提取压缩（零 LLM）
        Flux<OrchestrationEvent> orchestrationFlux = resolveCompressResult(conversation, conversationId, prep.historyBudget, accountId, progress, request.getContent(), allMessages)
                .flatMapMany(compressResult -> {
                    // RAG 上下文（CHAT 模式不需要检索知识库）
                    String ragContext = null;
                    if (intent != Intent.CHAT) {
                        try {
                            ragContext = buildRagContext(request.getContent(), conversation, accountId);
                        } catch (Exception e) {
                            log.warn("RAG 上下文构建失败: {}", e.getMessage());
                        }
                    }
                    log.info("编排准备: ragLen={}, historyBudget={}, contextLength={}", ragContext != null ? ragContext.length() : 0, prep.historyBudget, prep.contextLength);

                    // Epoch / 增强上下文（所有模式共享）
                    ContextResult epochResult = null;
                    String enhancedContext;
                    Set<UUID> importantMsgIds = null;
                    try {
                        epochResult = contextBuilder.buildEpochs(conversationId, compressResult, prep.historyBudget, allMessages);
                        epochResult = contextCache.apply(conversationId, epochResult);
                        if (epochResult != null && !epochResult.isEmpty()) {
                            enhancedContext = epochResult.toFlatString();
                        } else {
                            enhancedContext = compressResult.toSummaryText();
                            epochResult = null;
                        }
                        importantMsgIds = new HashSet<>(compressResult.getImportantMsgIds());
                    } catch (Exception e) {
                        log.warn("ContextBuilder 增强失败，降级到压缩摘要", e);
                        enhancedContext = compressResult.toSummaryText();
                    }

                    // 构建 LLM 消息（含图片、音频、文件附件富化）
                    List<Msg> msgs = buildLlmMessages(conversationId, accountId, llmImages, llmAudios, prep.contextLength, null, ragContext, null, importantMsgIds, providerId);
                    insertContextMessages(msgs, epochResult, enhancedContext);

                    // 工具决议：MCP → 语义匹配降级
                    ChatToolResult toolResult = resolveChatTools(conversation, request.getContent(), enhancedContext != null ? enhancedContext : compressResult.toSummaryText(), accountId);
                    if (toolResult.contextMsgs != null && !toolResult.contextMsgs.isEmpty()) {
                        // O2: 动态上下文作为独立 USER 消息，插入在 SYSTEM 和历史/epoch 消息之间
                        int insertIdx = 1;
                        for (Msg ctxMsg : toolResult.contextMsgs) {
                            msgs.add(insertIdx++, ctxMsg);
                        }
                    }

                    int toolCount = toolResult.tools != null ? toolResult.tools.size() : 0;
                    log.info("编排统一发送: intent={}, toolCount={}, msgsCount={}, enhancedContextLen={}", intent, toolCount, msgs.size(), enhancedContext != null ? enhancedContext.length() : 0);

                    List<ToolSpec> coreTools = toolResult.tools != null ? toolResult.tools.stream().map(s -> new ToolSpec(s.name(), s.description(), s.inputSchema())).toList() : null;
                    var pctx = resolveProjectFileContext(conversation, accountId);
                    String projectHint = pctx != null ? pctx.hint() : null;
                    String projectFilePath = pctx != null ? pctx.fileRootPath() : null;
                    boolean projectArchived = pctx != null && pctx.archived();

                    return squadOrchestrator.orchestrateStream(intent, request.getContent(), accountId, enhancedContext, conversationId, account, request.getTargetAgent(), coreTools, providerId, msgs, stream, projectHint, projectFilePath, projectArchived);
                });

        StringBuilder accumulatedContent = new StringBuilder();
        AtomicBoolean streamedContent = new AtomicBoolean(false);
        StringBuilder llmThinking = new StringBuilder();
        AtomicLong lastFlushTime = new AtomicLong(System.currentTimeMillis());
        firstChunkRef.put(prep.msgId, System.currentTimeMillis());
        AtomicLong thinkingStartMs = new AtomicLong(0);

        Mono<OrchestrationEvent> finalEventMono = orchestrationFlux.doOnNext(event -> dispatchOrchestrationEvent(event, prep.sink, accumulatedContent, streamedContent, llmThinking, lastFlushTime, thinkingStartMs, prep.assistantMsg, prep.msgId, routingDecisionId, pipelineSteps)).filter(event -> "final".equals(event.getType())).singleOrEmpty();

        Disposable disposable = finalEventMono.subscribe(
                finalEvent -> finalizeOrchestration(finalEvent, prep.assistantMsg,
                        conversationId, accountId, request.getContent(),
                        intent, accumulatedContent, llmThinking, streamedContent,
                        prep.sink, prep.msgId, routingDecisionId, account, pipelineSteps,
                        "编排", true),
                error -> {
                    log.error("编排流异常: conversationId={}", conversationId, error);
                    // 保留已积累的 partial content，不丢失 LLM 已生成内容
                    String partialContent = accumulatedContent.toString();
                    if (partialContent.isBlank()) {
                        partialContent = toUserMessage("编排执行失败", error);
                    }
                    prep.assistantMsg.setContent(partialContent);
                    if (!llmThinking.isEmpty()) {
                        prep.assistantMsg.setThinking(llmThinking.toString());
                    }
                    prep.assistantMsg.setStatus(MessageStatus.FAILED);
                    messageRepository.save(prep.assistantMsg);
                    // 失败时也持久化已收集的流水线步骤
                    if (!pipelineSteps.isEmpty()) {
                        try {
                            pipelineStepRepository.saveAll(new ArrayList<>(pipelineSteps));
                        } catch (Exception e) {
                            log.warn("保存流水线步骤失败(不影响主流程): {}", e.getMessage());
                        }
                    }
                    recordRouting(accountId, account.projectId(), conversationId, request.getContent(), intent.name(), "ERROR", false, partialContent, null);
                    prep.sink.tryEmitNext("{\"type\":\"error\",\"message\":\"" + escapeJson(prep.assistantMsg.getContent()) + "\"}");
                    prep.sink.tryEmitNext("{\"type\":\"response\",\"content\":\"" + escapeJson(prep.assistantMsg.getContent()) + "\"}");
                    streamManager.complete(prep.msgId);
                    streamingEngine.unregister(prep.msgId);
                });
        streamingEngine.register(prep.msgId, disposable);
        return streamManager.subscribe(prep.msgId).doOnCancel(() -> onClientDisconnect(prep.msgId));
    }


    /**
     * 分发编排事件到 SSE sink。stream/stream_thinking 直接转发为 response/thinking；
     * step_start/step_end 既维护 meta.chain 摘要（向后兼容），又发射独立结构化事件供前端渲染编排 UI。
     */
    private void dispatchOrchestrationEvent(OrchestrationEvent event, Sinks.Many<String> sink, StringBuilder content, AtomicBoolean streamed, StringBuilder llmThinking, AtomicLong lastFlushTime, AtomicLong thinkingStartMs, Message assistantMsg, UUID msgId, UUID routingDecisionId, ConcurrentLinkedDeque<PipelineStep> pipelineSteps) {
        switch (event.getType()) {
            case "stream" -> {
                String raw = event.getMessage();
                String text = raw != null ? SseSanitizer.sanitize(raw) : null;
                if (text != null && !text.isEmpty()) {
                    firstChunkRef.computeIfPresent(msgId, (id, refMs) -> {
                        log.info("首 token 到达: msgId={}, offsetFromContinueOrch={}ms", id, System.currentTimeMillis() - refMs);
                        return null;
                    });
                    content.append(raw); // 持久化保留原始内容，仅 SSE 输出脱敏
                    streamed.set(true);
                    if (thinkingStartMs.get() > 0 && assistantMsg.getThinkingDurationMs() == null) {
                        assistantMsg.setThinkingDurationMs((int) (System.currentTimeMillis() - thinkingStartMs.get()));
                    }
                    sink.tryEmitNext(SseFormatter.toJsonEvent("response", text));
                    tryFlushContent(assistantMsg, content, llmThinking, lastFlushTime);
                }
            }
            case "stream_thinking" -> {
                String raw = event.getMessage();
                String text = raw != null ? SseSanitizer.sanitize(raw) : null;
                if (text != null && !text.isEmpty()) {
                    if (thinkingStartMs.get() == 0) {
                        thinkingStartMs.set(System.currentTimeMillis());
                    }
                    llmThinking.append(raw); // 持久化保留原始内容
                    sink.tryEmitNext(SseFormatter.toJsonEvent("thinking", text));
                    tryFlushContent(assistantMsg, content, llmThinking, lastFlushTime);
                }
            }
            case "tool_call" -> {
                if (pipelineSteps != null) {
                    Map<String, Object> meta = event.getMetadata();
                    PipelineStep step = PipelineStep.builder().messageId(msgId).routingDecisionId(routingDecisionId)
                            .executionId(extractExecutionId(event.getMetadata()))
                            .stepType("tool_call").stepName(event.getMessage()).status("running")
                            .sequence(pipelineSteps.size() + 1).startedAt(java.time.OffsetDateTime.now())
                            .metadataJson(meta != null && !meta.isEmpty() ? meta : null)
                            .build();
                    // 记录命令到 inputSummary 以便前端直接展示
                    if (meta != null) {
                        Object cmd = meta.get("command");
                        if (cmd instanceof String c) {
                            step.setInputSummary(c);
                        }
                    }
                    pipelineSteps.add(step);
                    try {
                        pipelineStepRepository.save(step);
                    } catch (Exception e) {
                        log.warn("保存流水线步骤失败: {}", e.getMessage());
                    }
                }
            }
            case "tool_result" -> {
                if (pipelineSteps != null) {
                    pipelineSteps.stream().filter(s -> "tool_call".equals(s.getStepType()) && "running".equals(s.getStatus())
                                    && event.getMessage().equals(s.getStepName())).findFirst()
                            .ifPresent(s -> {
                                s.setStatus("completed");
                                s.setCompletedAt(java.time.OffsetDateTime.now());
                                Map<String, Object> meta = event.getMetadata();
                                if (meta != null) {
                                    Object output = meta.get("output");
                                    if (output instanceof String outStr && !outStr.isEmpty()) {
                                        s.setOutputSummary(outStr.length() > 500 ? outStr.substring(0, 500) + "…" : outStr);
                                    }
                                }
                                try {
                                    pipelineStepRepository.save(s);
                                } catch (Exception e) {
                                    log.warn("更新流水线步骤失败: {}", e.getMessage());
                                }
                            });
                }
            }
            case "step_start" -> {
                String name = event.getPhase() != null ? event.getPhase() : "";
                String msg = event.getMessage() != null ? event.getMessage() : "";
                log.info("编排 step_start: phase={}, message={}, msgId={}", name, msg, msgId);
                // 即时入库，即使编排中途失败也可查看
                if (pipelineSteps != null) {
                    PipelineStep step = PipelineStep.builder().messageId(msgId).routingDecisionId(routingDecisionId)
                            .executionId(extractExecutionId(event.getMetadata()))
                            .stepType(event.getPhase() != null ? event.getPhase() : event.getType()).stepName(event.getMessage()).status("running").sequence(pipelineSteps.size() + 1).startedAt(java.time.OffsetDateTime.now()).build();
                    pipelineSteps.add(step);
                    try {
                        pipelineStepRepository.save(step);
                    } catch (Exception e) {
                        log.warn("保存流水线步骤失败(step_start): {}", e.getMessage());
                    }
                }
                // 发射 SSE 事件供前端实时渲染编排 UI
                StringBuilder sse = new StringBuilder("{\"type\":\"step_start\"");
                SseFormatter.appendJsonStr(sse, "step", name);
                if (!msg.isBlank()) SseFormatter.appendJsonStr(sse, "message", msg);
                Map<String, Object> meta = event.getMetadata();
                if (meta != null) {
                    if (meta.get("executionId") instanceof String eid)
                        SseFormatter.appendJsonStr(sse, "executionId", eid);
                    if (meta.get("squadName") instanceof String sn) SseFormatter.appendJsonStr(sse, "squadName", sn);
                    if (meta.get("agentCount") instanceof Number ac)
                        SseFormatter.appendJsonNum(sse, "agentCount", ac.longValue());
                }
                sse.append("}");
                sink.tryEmitNext(sse.toString());
            }
            case "step_end" -> {
                String name = event.getPhase() != null ? event.getPhase() : "";
                String msg = event.getMessage() != null ? event.getMessage() : "";
                log.info("编排 step_end: phase={}, message={}, msgId={}", name, msg, msgId);
                // 即时更新入库
                if (pipelineSteps != null) {
                    String phase = event.getPhase();
                    Iterator<PipelineStep> it = pipelineSteps.descendingIterator();
                    while (it.hasNext()) {
                        PipelineStep s = it.next();
                        if ("running".equals(s.getStatus()) && (phase == null || phase.equals(s.getStepType()))) {
                            s.setStatus("completed");
                            s.setCompletedAt(java.time.OffsetDateTime.now());
                            if (s.getStartedAt() != null) {
                                s.setDurationMs((int) Duration.between(s.getStartedAt(), s.getCompletedAt()).toMillis());
                            }
                            Map<String, Object> meta = event.getMetadata();
                            if (meta != null) {
                                if (meta.get("agentName") instanceof String an) s.setAgentName(an);
                                if (meta.get("model") instanceof String mn) s.setModelName(mn);
                                if (meta.get("tokens") instanceof Number n) s.setTokenCount(n.intValue());
                                if (meta.get("output") instanceof String out) s.setOutputSummary(truncateStr(out, 500));
                            }
                            try {
                                pipelineStepRepository.save(s);
                            } catch (Exception e) {
                                log.warn("保存流水线步骤失败(step_end): {}", e.getMessage());
                            }
                            break;
                        }
                    }
                }
                // 发射 SSE 事件供前端更新编排状态
                StringBuilder sse = new StringBuilder("{\"type\":\"step_end\"");
                SseFormatter.appendJsonStr(sse, "step", name);
                Map<String, Object> meta = event.getMetadata();
                if (meta != null && meta.get("agentCount") instanceof Number ac) {
                    SseFormatter.appendJsonNum(sse, "agentCount", ac.longValue());
                }
                sse.append("}");
                sink.tryEmitNext(sse.toString());
            }
            case "final" -> {
                sink.tryEmitNext("{\"type\":\"final\"}");
            }
        }
    }

    /**
     * 从编排事件元数据中提取 executionId。
     */
    private static UUID extractExecutionId(Map<String, Object> metadata) {
        if (metadata == null) return null;
        Object val = metadata.get("executionId");
        if (val instanceof String s && !s.isBlank()) return UUID.fromString(s);
        return null;
    }

    /**
     * 周期持久化已积累的流内容到 DB，防止 LLM 中断导致内容丢失。
     * 每 {@link #FLUSH_INTERVAL_MS} 毫秒写一次，不阻塞流处理。
     */
    private void tryFlushContent(Message assistantMsg, StringBuilder content, StringBuilder llmThinking, AtomicLong lastFlushTime) {
        long now = System.currentTimeMillis();
        long last = lastFlushTime.get();
        if (now - last < FLUSH_INTERVAL_MS) return;
        if (lastFlushTime.compareAndSet(last, now)) {
            assistantMsg.setContent(content.toString());
            if (!llmThinking.isEmpty()) {
                assistantMsg.setThinking(llmThinking.toString());
            }
            try {
                messageRepository.save(assistantMsg);
            } catch (Exception e) {
                log.warn("周期保存消息失败(不影响流): msgId={}", assistantMsg.getMessageId(), e);
            }
        }
    }

    /**
     * 持久化编排最终结果、发射 SSE 事件、发布领域事件。
     * 同时用于正常消息和重新生成消息，通过 sourceLabel 区分日志标签。
     */
    private void finalizeOrchestration(OrchestrationEvent finalEvent, Message assistantMsg,
                                       UUID conversationId, UUID accountId, String userContent,
                                       Intent intent, StringBuilder accumulatedContent,
                                       StringBuilder accumulatedThinking, AtomicBoolean streamedContent,
                                       Sinks.Many<String> sink, UUID msgId, UUID routingDecisionId,
                                       Account account, ConcurrentLinkedDeque<PipelineStep> pipelineSteps,
                                       String sourceLabel, boolean publishEvent) {
        try {
            Map<String, Object> meta = finalEvent.getMetadata();
            String resultType = (String) meta.getOrDefault("type", "unknown");
            String content = accumulatedContent.toString();
            if (content.isBlank()) {
                content = (String) meta.getOrDefault("content", "");
            }
            if (content == null || content.isBlank()) {
                content = "抱歉，未能生成有效回复，请稍后重试。";
            }
            String llmThinking = !accumulatedThinking.isEmpty()
                    ? accumulatedThinking.toString()
                    : (String) meta.getOrDefault("thinking", "");

            assistantMsg.setContent(content);
            assistantMsg.setThinking(llmThinking != null && !llmThinking.isBlank() ? llmThinking : null);
            assistantMsg.setChain(routingDecisionId != null ? routingDecisionId.toString() : null);
            assistantMsg.setModel((String) meta.get("model"));
            if (meta.get("agentName") instanceof String an && !an.isBlank()) assistantMsg.setTargetAgent(an);
            if (meta.get("tokens") instanceof Number n) assistantMsg.setTotalTokens(n.intValue());
            if (meta.get("durationMs") instanceof Number n) assistantMsg.setDurationMs(n.intValue());
            // thinkingDurationMs 由 dispatchOrchestrationEvent 从 stream 时序计算，final 事件不覆盖
            if (meta.get("thinkingDurationMs") instanceof Number n && n.intValue() > 0)
                assistantMsg.setThinkingDurationMs(n.intValue());
            if (meta.get("thinkingTokens") instanceof Number n && n.intValue() > 0)
                assistantMsg.setThinkingTokens(n.intValue());
            assistantMsg.setStatus(MessageStatus.COMPLETED);
            messageRepository.save(assistantMsg);

            if (publishEvent) {
                eventPublisher.publishEvent(new MessageCompletedEvent(
                        assistantMsg.getMessageId(), conversationId, accountId, account.projectId(),
                        assistantMsg.getContent(), assistantMsg.getThinking(), assistantMsg.getModel(),
                        assistantMsg.getTotalTokens() != null ? assistantMsg.getTotalTokens() : 0,
                        assistantMsg.getDurationMs() != null ? assistantMsg.getDurationMs() : 0,
                        assistantMsg.getThinkingDurationMs() != null ? assistantMsg.getThinkingDurationMs() : 0,
                        assistantMsg.getThinkingTokens() != null ? assistantMsg.getThinkingTokens() : 0));
            }

            // 持久化流水线步骤
            if (pipelineSteps != null && !pipelineSteps.isEmpty()) {
                try {
                    pipelineStepRepository.saveAll(new ArrayList<>(pipelineSteps));
                } catch (Exception e) {
                    log.warn("保存流水线步骤失败({}): {}", sourceLabel, e.getMessage());
                }
            }

            if (!streamedContent.get()) {
                if (llmThinking != null && !llmThinking.isBlank())
                    sink.tryEmitNext(SseFormatter.toJsonEvent("thinking", llmThinking));
                sink.tryEmitNext(SseFormatter.toJsonEvent("response", content));
            }

            String agentName = (String) meta.get("agentName");

            // 在 SSE meta 事件发出前，记录最终路由决策（intent.name() = CHAT/SINGLE_AGENT/SQUAD）
            // 并更新 chain 指向最终决策，确保前端实时 SSE 收到正确的 chain
            String resultDesc = Intent.CHAT.equals(intent) ? "聊天回复完成"
                    : sourceLabel + "完成: " + resultType;
            Map<String, Object> routingContext = new HashMap<>(Map.of("orchestrationType", resultType));
            String selectedAgent = agentName;
            if (meta != null && meta.get("agentNames") instanceof List<?> rawList) {
                List<String> squadAgentNames = rawList.stream()
                        .filter(String.class::isInstance)
                        .map(String.class::cast)
                        .toList();
                if (!squadAgentNames.isEmpty()) {
                    selectedAgent = String.join(", ", squadAgentNames);
                    routingContext.put("agentNames", squadAgentNames);
                }
            }
            UUID finalRoutingId = recordRouting(accountId, account.projectId(), conversationId, userContent,
                    intent.name(), selectedAgent, true, resultDesc, routingContext);
            // 更新消息 chain 指向最终路由决策（而非中间 INTENT_CLASSIFICATION）
            if (finalRoutingId != null) {
                assistantMsg.setChain(finalRoutingId.toString());
                messageRepository.save(assistantMsg);
            }
            performanceService.recordSuccess(accountId, intent.name());

            String chainStr = finalRoutingId != null ? finalRoutingId.toString()
                    : (routingDecisionId != null ? routingDecisionId.toString() : null);
            // thinkingDurationMs 优先用 assistantMsg 中已计算的值（来自 stream_thinking 时序）
            int thinkingDuration = assistantMsg.getThinkingDurationMs() != null ? assistantMsg.getThinkingDurationMs()
                    : meta.get("thinkingDurationMs") instanceof Number n ? n.intValue() : 0;
            sink.tryEmitNext(SseFormatter.buildMetaEvent((String) meta.get("model"),
                    meta.get("tokens") instanceof Number n ? n.intValue() : null,
                    meta.get("durationMs") instanceof Number n ? n.intValue() : 0,
                    thinkingDuration,
                    meta.get("thinkingTokens") instanceof Number n ? n.intValue() : null,
                    msgId.toString(), chainStr));

            // 发射生成版本元信息
            if (assistantMsg.getGenerationGroup() != null) {
                int total = messageRepository.countByGenerationGroup(assistantMsg.getGenerationGroup());
                sink.tryEmitNext("{\"type\":\"meta\",\"messageId\":\"" + assistantMsg.getMessageId()
                        + "\",\"generationGroup\":\"" + assistantMsg.getGenerationGroup()
                        + "\",\"generationTotal\":" + total + "}");
            }

            log.info("{}流完成: conversationId={}, intent={}, type={}",
                    sourceLabel, conversationId, intent, resultType);

            scheduleAsyncCompression(conversationId, accountId, assistantMsg.getMessageId());
        } catch (Exception e) {
            log.error("{}结果持久化异常", sourceLabel, e);
            assistantMsg.setStatus(MessageStatus.FAILED);
            if (assistantMsg.getContent() == null || assistantMsg.getContent().isBlank()) {
                assistantMsg.setContent(toUserMessage("编排处理异常", e));
            }
            messageRepository.save(assistantMsg);
            sink.tryEmitNext("{\"type\":\"response\",\"content\":\""
                    + escapeJson(assistantMsg.getContent()) + "\"}");
        } finally {
            streamManager.complete(msgId);
            streamingEngine.unregister(msgId);
        }
    }

    /**
     * 重新生成 AI 回复。重新走完整编排：意图分类 → 上下文构建 → 编排执行。
     * 旧 AI 消息保留在 DB 中并分配 generationGroup（支持版本切换），
     * 但在构建 LLM 历史时排除。
     */
    public Flux<String> regenerateMessage(UUID accountId, UUID conversationId, RegenerateRequest request) {
        Conversation conversation = findOwned(accountId, conversationId);

        // 找到要重新生成的 AI 消息
        Message aiMsg = messageRepository.findById(request.getMessageId())
                .orElseThrow(() -> new DomainException("消息不存在: " + request.getMessageId()));
        if (!aiMsg.getConversationId().equals(conversationId)) {
            throw new DomainException("消息不属于该对话");
        }
        if (!aiMsg.isAssistant()) {
            throw new DomainException("只能重新生成 AI 回复");
        }

        int newGenIndex = (aiMsg.getGenerationIndex() != null ? aiMsg.getGenerationIndex() : 0) + 1;

        // 保留旧 AI 消息，分配 generationGroup
        UUID generationGroup = aiMsg.getGenerationGroup() != null ? aiMsg.getGenerationGroup() : UUID.randomUUID();
        aiMsg.setGenerationGroup(generationGroup);
        messageRepository.save(aiMsg);

        // 获取原始用户消息：严格按 sortOrder + role==USER 双条件定位前一条用户消息
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
        int contextLength = resolveContextLength(providerId, accountId);
        int maxPromptTokens = (int) (contextLength * resolveBudgetRatio(providerId, accountId));
        int historyBudget = (int) (maxPromptTokens * 0.85);

        // 创建 RUNNING 占位消息（与旧消息同一 generationGroup）
        Message assistantMsg = createAssistantMessage(conversation, accountId);
        assistantMsg.setGenerationIndex(newGenIndex);
        assistantMsg.setGenerationGroup(generationGroup);
        assistantMsg = messageRepository.save(assistantMsg);
        final var finalAssistantMsg = assistantMsg;

        UUID msgId = assistantMsg.getMessageId();
        Sinks.Many<String> sink = streamManager.create(msgId);
        var account = new Account(accountId, conversation.getProjectId());

        log.info("重新生成编排流: conversationId={}, msgId={}, providerId={}, contextLength={}",
                conversationId, msgId, providerId, contextLength);

        Consumer<String> progress = msg -> log.debug("重新生成编排进度: {}", msg);

        // 意图分类 → 上下文构建 → 编排执行（与正常发送路径一致）
        StringBuilder accumulatedContent = new StringBuilder();
        AtomicBoolean streamedContent = new AtomicBoolean(false);
        StringBuilder llmThinking = new StringBuilder();
        AtomicLong lastFlushTime = new AtomicLong(System.currentTimeMillis());
        firstChunkRef.put(msgId, System.currentTimeMillis());
        AtomicLong thinkingStartMs = new AtomicLong(0);
        ConcurrentLinkedDeque<PipelineStep> pipelineSteps = new ConcurrentLinkedDeque<>();
        AtomicReference<UUID> routingDecisionIdRef = new AtomicReference<>();
        AtomicReference<Intent> intentRef = new AtomicReference<>();

        Flux<OrchestrationEvent> orchestrationFlux = squadOrchestrator.resolveIntent(userContent, accountId,
                        conversation.getProjectId(), conversationId)
                .flatMapMany(intent -> {
                    log.info("重新生成意图分类: conversationId={}, intent={}", conversationId, intent);
                    intentRef.set(intent);

                    // 记录路由决策（真实意图，非 REGENERATE）
                    UUID routingDecisionId = recordRouting(accountId, conversation.getProjectId(),
                            conversationId, userContent, intent.name(), null, true,
                            "重新生成消息", Map.of("regenerate", true,
                                    "originalMessageId", aiMsg.getMessageId().toString()));
                    if (routingDecisionId != null) {
                        finalAssistantMsg.setChain(routingDecisionId.toString());
                        messageRepository.save(finalAssistantMsg);
                    }
                    routingDecisionIdRef.set(routingDecisionId);

                    sink.tryEmitNext(SseFormatter.buildMetaEvent(null, null, 0, 0,
                            msgId.toString(),
                            routingDecisionId != null ? routingDecisionId.toString() : null));

                    // 延迟压缩 + 上下文构建
                    return resolveCompressResult(conversation, conversationId, historyBudget, accountId, progress, userContent)
                            .flatMapMany(compressResult -> {

                                // RAG 上下文
                                String ragContext;
                                try {
                                    ragContext = buildRagContext(userContent, conversation, accountId);
                                } catch (Exception e) {
                                    log.warn("RAG 上下文构建失败(regenerate): {}", e.getMessage());
                                    ragContext = null;
                                }

                                // Epoch / 增强上下文
                                ContextResult epochResult = null;
                                String enhancedContext;
                                try {
                                    epochResult = contextBuilder.buildEpochs(conversationId, compressResult, historyBudget);
                                    epochResult = contextCache.apply(conversationId, epochResult);
                                    if (epochResult != null && !epochResult.isEmpty()) {
                                        enhancedContext = epochResult.toFlatString();
                                    } else {
                                        enhancedContext = compressResult.toSummaryText();
                                        epochResult = null;
                                    }
                                } catch (Exception e) {
                                    log.warn("ContextBuilder 增强失败(regenerate)，降级到压缩摘要", e);
                                    enhancedContext = compressResult.toSummaryText();
                                }

                                // 构建 LLM 消息（排除旧 AI 消息）
                                List<Msg> msgs = buildLlmMessages(conversationId, accountId,
                                        regenerateImages, regenerateAudios, contextLength,
                                        aiMsg.getMessageId(), ragContext, null, null, providerId);
                                insertContextMessages(msgs, epochResult, enhancedContext);

                                // 工具决议
                                ChatToolResult toolResult = resolveChatTools(conversation, userContent,
                                        enhancedContext != null ? enhancedContext : compressResult.toSummaryText(),
                                        accountId);
                                if (toolResult.contextMsgs != null && !toolResult.contextMsgs.isEmpty()) {
                                    int insertIdx = 1;
                                    for (Msg ctxMsg : toolResult.contextMsgs) {
                                        msgs.add(insertIdx++, ctxMsg);
                                    }
                                }

                                List<ToolSpec> coreTools = toolResult.tools != null
                                        ? toolResult.tools.stream()
                                        .map(s -> new ToolSpec(s.name(), s.description(), s.inputSchema()))
                                        .toList()
                                        : null;

                                var pctx = resolveProjectFileContext(conversation, accountId);
                                String projectHint = pctx != null ? pctx.hint() : null;
                                String projectFilePath = pctx != null ? pctx.fileRootPath() : null;
                                boolean projectArchived = pctx != null && pctx.archived();
                                return squadOrchestrator.orchestrateStream(intent, userContent, accountId,
                                        enhancedContext, conversationId, account, null, coreTools,
                                        providerId, msgs, request.isStream(), projectHint, projectFilePath, projectArchived);
                            });
                });

        // 与 sendMessage 一致：内部 subscribe 驱动编排，dispatch 推入 sink，外部返回 streamManager.subscribe 供 SSE 读取
        Mono<OrchestrationEvent> finalEventMono = orchestrationFlux
                .doOnNext(event -> dispatchOrchestrationEvent(event, sink,
                        accumulatedContent, streamedContent, llmThinking, lastFlushTime, thinkingStartMs,
                        finalAssistantMsg, msgId, routingDecisionIdRef.get(), pipelineSteps))
                .filter(event -> "final".equals(event.getType()))
                .singleOrEmpty();

        Disposable disposable = finalEventMono.subscribe(
                finalEvent -> persistRegenerateResult(finalEvent, finalAssistantMsg,
                        conversationId, accountId, userContent, intentRef.get(), accumulatedContent, llmThinking,
                        streamedContent, sink, msgId, routingDecisionIdRef.get(), account, pipelineSteps),
                error -> {
                    log.error("重新生成编排流异常: conversationId={}", conversationId, error);
                    // 保留已积累的 partial content，不丢失 LLM 已生成内容
                    String partialContent = accumulatedContent.toString();
                    if (partialContent.isBlank()) {
                        partialContent = toUserMessage("重新生成失败", error);
                    }
                    finalAssistantMsg.setContent(partialContent);
                    if (!llmThinking.isEmpty()) {
                        finalAssistantMsg.setThinking(llmThinking.toString());
                    }
                    finalAssistantMsg.setStatus(MessageStatus.FAILED);
                    messageRepository.save(finalAssistantMsg);
                    sink.tryEmitNext("{\"type\":\"response\",\"content\":\""
                            + escapeJson(finalAssistantMsg.getContent()) + "\"}");
                    streamManager.complete(msgId);
                    streamingEngine.unregister(msgId);
                });
        streamingEngine.register(msgId, disposable);
        return streamManager.subscribe(msgId).doOnCancel(() -> onClientDisconnect(msgId));
    }

    /**
     * 持久化重新生成编排结果（委托给 {@link #finalizeOrchestration}）。
     */
    private void persistRegenerateResult(OrchestrationEvent finalEvent, Message assistantMsg,
                                         UUID conversationId, UUID accountId, String userContent,
                                         Intent intent, StringBuilder accumulatedContent,
                                         StringBuilder accumulatedThinking, AtomicBoolean streamedContent,
                                         Sinks.Many<String> sink, UUID msgId, UUID routingDecisionId,
                                         Account account, ConcurrentLinkedDeque<PipelineStep> pipelineSteps) {
        finalizeOrchestration(finalEvent, assistantMsg, conversationId, accountId, userContent,
                intent, accumulatedContent, accumulatedThinking, streamedContent,
                sink, msgId, routingDecisionId, account, pipelineSteps,
                "重新生成编排", false);
    }

    /**
     * 续接指定消息的流。优先从活跃的 Stream Sink 订阅（续接真实流），
     * Sink 不存在时回退 DB 持久化内容。
     */
    public Flux<String> subscribeStream(UUID accountId, UUID conversationId, UUID messageId) {
        Message message = messageRepository.findById(messageId).orElseThrow(() -> ResourceNotFoundException.notFound("消息", messageId));
        if (!message.getConversationId().equals(conversationId) || !message.getAccountId().equals(accountId)) {
            throw ResourceNotFoundException.notFound("消息", messageId);
        }

        // Stream Sink 仍存在（任务活跃或已完成但缓存未过期），从中取事件可续接完整流
        if (streamManager.isActive(messageId)) {
            return streamManager.subscribe(messageId);
        }

        // Sink 已不存在（5 分钟 TTL 过期或被移除），回退 DB 持久化内容
        if (message.getStatus() == MessageStatus.COMPLETED) {
            return Flux.just(SseFormatter.toJsonEvent("response", message.getContent() != null ? message.getContent() : ""), SseFormatter.buildMetaEvent(message.getModel(), message.getTotalTokens(), message.getDurationMs() != null ? message.getDurationMs() : 0, message.getThinkingDurationMs() != null ? message.getThinkingDurationMs() : 0, message.getMessageId() != null ? message.getMessageId().toString() : null, message.getChain()));
        }

        // 仍在运行中，返回当前 DB 中的部分内容（来自周期 flush）
        if (message.getContent() != null && !message.getContent().isEmpty()) {
            return Flux.just(SseFormatter.toJsonEvent("response", message.getContent()));
        }
        return Flux.empty();
    }

    /**
     * 取消指定消息的后台流（API 调用入口，含所有权校验）。
     */
    public void cancelStream(UUID accountId, UUID conversationId, UUID messageId) {
        findOwned(accountId, conversationId);
        cleanupStream(messageId);
    }

    /**
     * 取消指定消息的后台流（内部清理，无所有权校验）。
     */
    private void cleanupStream(UUID messageId) {
        streamingEngine.cancel(messageId);
    }

    /**
     * SSE 客户端断开连接时调用（轻量，不取消后台任务）。
     * 后台 LLM 继续执行，sink 保持活跃，客户端可重新订阅续接。
     */
    private void onClientDisconnect(UUID messageId) {
        log.info("SSE 客户端断开连接，后台任务继续执行: msgId={}", messageId);
    }

    /**
     * 获取消息列表（默认分页）。
     */
    public MessagePageResponse getMessages(UUID accountId, UUID conversationId) {
        return getMessages(accountId, conversationId, null, DEFAULT_PAGE_LIMIT);
    }

    /**
     * 分页查询消息（支持游标分页），返回带 hasMore 标记的分页结果。
     */
    public MessagePageResponse getMessages(UUID accountId, UUID conversationId, Integer beforeSortOrder, int limit) {
        findOwned(accountId, conversationId);
        List<Message> msgs;
        if (beforeSortOrder != null) {
            msgs = messageRepository.findBeforeSortOrder(conversationId, beforeSortOrder, limit);
        } else {
            msgs = messageRepository.findLatestByConversationId(conversationId, limit);
        }

        // 判断是否还有更多：只要还有比本页最旧消息更旧的记录即可
        boolean hasMore = false;
        if (!msgs.isEmpty()) {
            int oldestInPage = msgs.get(0).getSortOrder();
            hasMore = messageRepository.countBeforeSortOrder(conversationId, oldestInPage) > 0;
        }

        List<Message> deduped = deduplicateGenerations(msgs);
        Map<UUID, Integer> genCounts = preloadGenerationCounts(deduped);
        Map<UUID, Message> replyToMap = preloadReplyToMessages(deduped);
        List<MessageResponse> responses = deduped.stream()
                .map(m -> toMessageResponse(m, genCounts.get(m.getGenerationGroup()), replyToMap))
                .toList();
        return new MessagePageResponse(responses, hasMore);
    }

    /**
     * 批量预查 generationGroup 消息数，避免 N+1。
     */
    private Map<UUID, Integer> preloadGenerationCounts(List<Message> messages) {
        Map<UUID, Integer> counts = new LinkedHashMap<>();
        for (Message msg : messages) {
            if (msg.getGenerationGroup() != null && !counts.containsKey(msg.getGenerationGroup())) {
                counts.put(msg.getGenerationGroup(), messageRepository.countByGenerationGroup(msg.getGenerationGroup()));
            }
        }
        return counts;
    }

    /**
     * 批量预查被引用消息，避免 N+1 且支持跨分页引用。
     */
    private Map<UUID, Message> preloadReplyToMessages(List<Message> messages) {
        Map<UUID, Message> replyToMap = new LinkedHashMap<>();
        for (Message msg : messages) {
            UUID replyToId = msg.getReplyToId();
            if (replyToId != null && !replyToMap.containsKey(replyToId)) {
                messageRepository.findById(replyToId).ifPresent(m -> replyToMap.put(replyToId, m));
            }
        }
        return replyToMap;
    }

    /**
     * 获取指定消息的所有生成版本（同一 generationGroup 内按 generationIndex 升序）。
     */
    public List<MessageResponse> getGenerations(UUID accountId, UUID conversationId, UUID messageId) {
        findOwned(accountId, conversationId);
        Message message = messageRepository.findById(messageId).orElseThrow(() -> ResourceNotFoundException.notFound("消息", messageId));
        if (message.getGenerationGroup() == null) return List.of();
        return messageRepository.findByGenerationGroup(message.getGenerationGroup()).stream().map(this::toMessageResponse).toList();
    }

    /**
     * 对于有 generationGroup 的消息，只保留每组中 generationIndex 最大的那条。
     * 没有 generationGroup 的消息全部保留。
     */
    private List<Message> deduplicateGenerations(List<Message> messages) {
        Map<UUID, Message> latestPerGroup = new LinkedHashMap<>();
        List<Message> result = new ArrayList<>();
        for (Message msg : messages) {
            if (msg.getGenerationGroup() != null) {
                Message existing = latestPerGroup.get(msg.getGenerationGroup());
                if (existing == null || msg.getGenerationIndex() > existing.getGenerationIndex()) {
                    latestPerGroup.put(msg.getGenerationGroup(), msg);
                }
            } else {
                result.add(msg);
            }
        }
        result.addAll(latestPerGroup.values());
        // 按 sortOrder 重新排序
        result.sort(java.util.Comparator.comparingInt(Message::getSortOrder));
        return result;
    }

    /**
     * 删除指定消息。
     */
    public void deleteMessage(UUID accountId, UUID messageId) {
        Message message = messageRepository.findById(messageId).orElseThrow(() -> ResourceNotFoundException.notFound("消息", messageId));
        if (!message.getAccountId().equals(accountId)) {
            throw ResourceNotFoundException.notFound("消息", messageId);
        }
        messageRepository.delete(messageId);
    }

    /**
     * 统计对话消息数。
     */
    public int countMessages(UUID accountId, UUID conversationId) {
        findOwned(accountId, conversationId);
        return messageRepository.countByConversationId(conversationId);
    }

    /**
     * 获取消息的编排流水线步骤列表（含子步骤层次）。
     */
    public List<PipelineStepResponse> getPipelineSteps(UUID accountId, UUID messageId) {
        // 校验消息所有权
        Message message = messageRepository.findById(messageId).orElseThrow(() -> ResourceNotFoundException.notFound("消息", messageId));
        if (!message.getAccountId().equals(accountId)) {
            throw ResourceNotFoundException.notFound("消息", messageId);
        }
        List<PipelineStep> steps = pipelineStepRepository.findByMessageId(messageId);
        // 构建父子层次：先收集所有顶层步骤（无 parent），再递归填充 children
        Map<UUID, List<PipelineStep>> childrenByParent = new LinkedHashMap<>();
        for (PipelineStep s : steps) {
            UUID pid = s.getParentStepId();
            if (pid != null) {
                childrenByParent.computeIfAbsent(pid, k -> new java.util.ArrayList<>()).add(s);
            }
        }
        Function<PipelineStep, PipelineStepResponse> toResponse = new Function<>() {
            @Override
            public PipelineStepResponse apply(PipelineStep s) {
                List<PipelineStepResponse> childResponses = null;
                List<PipelineStep> childSteps = childrenByParent.get(s.getStepId());
                if (childSteps != null) {
                    childResponses = childSteps.stream().map(this).toList();
                }
                return PipelineStepResponse.builder().stepId(s.getStepId()).messageId(s.getMessageId()).routingDecisionId(s.getRoutingDecisionId()).executionId(s.getExecutionId()).stepType(s.getStepType()).stepName(s.getStepName()).status(s.getStatus()).sequence(s.getSequence()).parentStepId(s.getParentStepId()).startedAt(s.getStartedAt()).completedAt(s.getCompletedAt()).durationMs(s.getDurationMs()).inputSummary(s.getInputSummary()).outputSummary(s.getOutputSummary()).agentName(s.getAgentName()).modelName(s.getModelName()).tokenCount(s.getTokenCount()).metadataJson(s.getMetadataJson()).createdAt(s.getCreatedAt()).children(childResponses).build();
            }
        };
        return steps.stream().filter(s -> s.getParentStepId() == null).map(toResponse).toList();
    }

    /**
     * 为消息打分（点赞/点踩）。
     */
    public MessageResponse rateMessage(UUID accountId, UUID messageId, String rating) {
        Message message = messageRepository.findById(messageId).orElseThrow(() -> ResourceNotFoundException.notFound("消息", messageId));
        if (!message.getAccountId().equals(accountId)) {
            throw ResourceNotFoundException.notFound("消息", messageId);
        }
        message.setRating(rating);
        message = messageRepository.save(message);
        return toMessageResponse(message);
    }

    /**
     * 错误消息中的 JSON 转义
     */
    private static String escapeJson(String s) {
        return JsonUtil.escapeJson(s);
    }

    /**
     * 截断长文本为指定最大长度。
     */
    private static String truncateStr(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    /**
     * 获取上一轮助手回复的最后一句话，用于本地意图分类的上下文增强。
     * 当用户简短回应（如"需要""好的"）时，仅用最后一句话辅助判断，避免语义泛滥。
     */
    private String getLastAssistantContent(UUID conversationId) {
        try {
            List<Message> recent = messageRepository.findLatestByConversationId(conversationId, 5);
            for (Message msg : recent) {
                if (!msg.isUser()) {
                    String content = msg.getContent();
                    if (content != null && !content.isBlank()) {
                        return extractLastSentence(content, 100);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("获取上一轮助手回复失败: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 截取文本最后一句，限制最大长度。
     * 从中/英文句末标点处截断，取最后一句话作为语义上下文。
     */
    private static String extractLastSentence(String text, int maxLen) {
        if (text == null || text.isBlank()) return null;
        String trimmed = text.strip();
        // 中英文句末标点
        int lastBoundary = -1;
        for (int i = trimmed.length() - 1; i >= 0; i--) {
            char c = trimmed.charAt(i);
            if (c == '。' || c == '！' || c == '？' || c == '.' || c == '!' || c == '?' ||
                c == '\n' || c == '\r') {
                lastBoundary = i;
                break;
            }
        }
        String lastSentence;
        if (lastBoundary >= 0 && lastBoundary < trimmed.length() - 1) {
            lastSentence = trimmed.substring(lastBoundary + 1).strip();
        } else {
            lastSentence = trimmed;
        }
        return lastSentence.length() > maxLen ? lastSentence.substring(0, maxLen) : lastSentence;
    }

    /**
     * 记录路由决策（不影响主流程），返回决策 ID 或 null。
     */
    private UUID recordRouting(UUID accountId, UUID projectId, UUID conversationId, String taskDesc, String strategy, String agentName, boolean success, String reasoning, Map<String, Object> context) {
        UUID decisionId = routingDecisionRecorder.record(RoutingDecisionRecorder.RecordRequest.simple(accountId, projectId, conversationId, taskDesc, strategy, agentName, success, reasoning, context));
        // 记录策略使用统计
        if (decisionId != null) {
            performanceService.recordUsage(accountId, strategy, success ? 1.0 : 0.0);
        }
        return decisionId;
    }

    /**
     * 创建助理消息占位（RUNNING 状态）。
     */
    private Message createAssistantMessage(Conversation conversation, UUID accountId) {
        return Message.builder().conversationId(conversation.getConversationId()).accountId(accountId).projectId(conversation.getProjectId()).role(Message.ROLE_ASSISTANT).content("").contentType("text").status(MessageStatus.RUNNING).build();
    }

    /**
     * 构建用户消息实体。
     */
    private static Message buildUserMessage(UUID conversationId, UUID accountId, Conversation conversation, SendMessageRequest request) {
        return Message.builder().conversationId(conversationId).accountId(accountId).projectId(conversation.getProjectId()).role(Message.ROLE_USER).content(request.getContent()).contentType(request.getContentType() != null ? request.getContentType() : "text").targetAgent(request.getTargetAgent()).replyToId(request.getReplyToId()).status(MessageStatus.COMPLETED).images(MessageAttachmentsSerializer.serializeImages(request.getImages())).audios(MessageAttachmentsSerializer.serializeAudios(request.getAudios())).attachments(MessageAttachmentsSerializer.serializeAttachments(request.getAttachments())).build();
    }


    /**
     * 将用户上传的文件复制到项目沙盒 uploads/ 目录，确保 file_read 等工具可访问。
     * 大文件跳过 LLM 内容嵌入时，文件仍在沙盒中可供工具读取。
     *
     * @return 复制失败的文件名列表（空列表表示全部成功）
     */
    private List<String> copyAttachmentsToSandbox(UUID accountId, Conversation conversation, SendMessageRequest request) {
        UUID projectId = conversation.getProjectId();
        if (projectId == null) return List.of();

        List<Map<String, Object>> attachments = request.getAttachments();
        if (attachments == null || attachments.isEmpty()) return List.of();

        String sandboxPath = groupService.getProjectRootPath(accountId, projectId);
        if (sandboxPath == null) return List.of();

        Path uploadDir = Paths.get(sandboxPath, "uploads");
        List<String> failedFiles = new java.util.ArrayList<>();

        for (Map<String, Object> att : attachments) {
            String fileIdStr = att.get("fileId") != null ? att.get("fileId").toString() : null;
            String fileName = (String) att.get("fileName");
            if (fileIdStr == null || fileName == null || fileName.isBlank()) continue;

            String safeName = sanitizeFileName(fileName);
            if (safeName == null) {
                log.warn("跳过不安全的文件名: {}", fileName);
                failedFiles.add(fileName);
                continue;
            }

            try {
                byte[] bytes = fileStorageService.loadBytes(accountId, UUID.fromString(fileIdStr));
                if (bytes == null) continue;
                Files.createDirectories(uploadDir);
                Files.write(uploadDir.resolve(safeName), bytes);
                log.info("上传文件已复制到沙盒工作目录: {} ({} bytes)", safeName, bytes.length);
            } catch (Exception e) {
                log.warn("复制上传文件到沙盒失败: fileId={}, name={}", fileIdStr, fileName, e);
                failedFiles.add(fileName);
            }
        }

        return failedFiles;
    }

    /** 净化文件名，防止路径穿越。仅保留文件名最后一部分，过滤不可见字符。 */
    private static String sanitizeFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) return null;
        // 仅保留路径中的最后一部分（去除目录遍历）
        String safe = Path.of(fileName).getFileName().toString();
        // 过滤掉空名、点号（. 和 ..）、以点开头（隐藏文件）和空字符串
        if (safe.isEmpty() || safe.equals(".") || safe.equals("..") || safe.startsWith(".")) return null;
        return safe;
    }


    /**
     * 构建核心 Message 列表，供编排路径使用。
     * SYSTEM 消息使用纯静态的 CHAT_SYSTEM 基座以启用 prompt 缓存前缀命中，
     * 动态上下文（用户画像/情境闪现/目标/文件快照）通过独立 USER 消息注入。
     */
    private List<Msg> buildLlmMessages(UUID conversationId, UUID accountId, List<String> images, List<String> audios, int contextLength, UUID excludeMessageId, String ragContext, String compressedContext, Set<UUID> protectMsgIds, UUID providerId) {
        List<EnrichedMessage> enriched = enrichMessages(conversationId, images, audios, contextLength, excludeMessageId, ragContext, protectMsgIds, accountId, providerId);

        CoreMessageBuilder coreBuilder = new CoreMessageBuilder(fileStorageService);
        // 纯静态 SYSTEM 基座，不含用户画像等动态内容
        List<Msg> msgs = coreBuilder.build(ChatPrompts.CHAT_SYSTEM.content(), enriched, excludeMessageId, accountId);

        // 注入上下文摘要（contextText 或 compressStream 输出）
        if (compressedContext != null && !compressedContext.isBlank()) {
            msgs.add(1, Msg.of(Role.USER, ChatPrompts.contextInjection(compressedContext).content()));
        }
        return msgs;
    }

    /**
     * 加载、截断并富化对话消息，返回富化后的消息列表。
     * {@link #buildLlmMessages} 共享此步骤。
     */
    private List<EnrichedMessage> enrichMessages(UUID conversationId, List<String> images, List<String> audios, int contextLength, UUID excludeMessageId, String ragContext, Set<UUID> protectMsgIds, UUID accountId, UUID providerId) {
        // 1. 加载消息 — Adapter 返回 newest-first（JPA DESC）
        List<Message> messages = new ArrayList<>(messageRepository.findLatestByConversationId(conversationId, MAX_MESSAGES_LOAD));

        // 2. Token 感知截断（oldest-first 传给截断器）
        Collections.reverse(messages);
        int maxPromptTokens = (int) (contextLength * resolveBudgetRatio(providerId, accountId));
        List<Message> truncated = truncateWithProtection(messages, ChatPrompts.CHAT_SYSTEM.content(), maxPromptTokens, protectMsgIds != null ? protectMsgIds : Collections.emptySet());
        // 截断后转回 oldest-first 以保证 LLM 收到正确的时序上下文
        Collections.reverse(truncated);

        // 3. 确定目标消息 ID（最后一条非排除的用户消息，用于精确的图片/音频/RAG 注入）
        UUID targetMessageId = findTargetMessageId(truncated, excludeMessageId);

        // 4. 检查模型多模态能力
        boolean visionSupported = isVisionSupported(providerId, accountId);

        // 5. 内容富化：管道链顺序执行
        ContentEnricherPipeline pipeline = new ContentEnricherPipeline()
                .addEnricher(new FileAttachmentEnricher())
                .addEnricher(new DocumentAttachmentEnricher(documentTextExtractor, fileStorageService))
                .addEnricher(new ReplyQuoteEnricher(messageRepository))
                .addEnricher(new RagContextEnricher(ragContext, targetMessageId));
        if (visionSupported) {
            pipeline.addEnricher(new ImageAttachmentEnricher(images, targetMessageId));
        }
        pipeline.addEnricher(new AudioAttachmentEnricher(audios, targetMessageId));
        return pipeline.enrich(truncated, messages);
    }

    /** 检查当前模型是否支持视觉多模态。 */
    private boolean isVisionSupported(UUID providerId, UUID accountId) {
        try {
            var provider = providerId != null
                    ? modelRouter.getProvider(providerId)
                    : modelRouter.getDefaultProvider(accountId);
            return provider != null && provider.supportsCapability("vision");
        } catch (Exception e) {
            log.warn("检查模型视觉能力失败，默认不支持: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 受保护消息 ID 感知截断：保留所有受保护消息（taskGoal + 重要用户消息），
     * 其余消息按 newest-first 截断。是 TokenBudgetTruncator 的 Phase 2 替代。
     * <p>
     * 输入：messages 为 newest-first。
     * 输出：截断后的消息列表，仍为 newest-first。
     */
    private List<Message> truncateWithProtection(List<Message> messages, String systemPrompt, int maxPromptTokens, Set<UUID> protectMsgIds) {
        int baseTokens = estimateTokens(systemPrompt);

        // 先计算受保护消息的总 token
        List<Message> protectedMsgs = new ArrayList<>();
        List<Message> unprotectedMsgs = new ArrayList<>();
        for (Message msg : messages) {
            if (msg.getMessageId() != null && protectMsgIds.contains(msg.getMessageId())) {
                protectedMsgs.add(msg);
            } else {
                unprotectedMsgs.add(msg);
            }
        }

        int protectedTokens = estimateMessagesTokens(protectedMsgs, systemPrompt);
        int remainingBudget = maxPromptTokens - protectedTokens;

        // 受保护消息已超预算时，只保留最新受保护消息
        if (remainingBudget <= 0) {
            log.warn("受保护消息已超预算 ({} > {}), 仅保留最新的受保护消息", protectedTokens, maxPromptTokens);
            List<Message> squeezed = new ArrayList<>();
            int budget = baseTokens;
            for (Message msg : protectedMsgs) {
                int tokens = estimateMessageTokens(msg);
                if (budget + tokens <= maxPromptTokens) {
                    squeezed.add(msg);
                    budget += tokens;
                } else {
                    break;
                }
            }
            return squeezed;
        }

        // 从非受保护消息中截取最新部分
        List<Message> result = new ArrayList<>(protectedMsgs);
        int budget = protectedTokens;
        for (Message msg : unprotectedMsgs) {
            int tokens = estimateMessageTokens(msg);
            if (budget + tokens <= maxPromptTokens) {
                result.add(msg);
                budget += tokens;
            } else {
                break;
            }
        }

        // 按 newest-first 排序（受保护消息可能在任意位置，但须按原时序排列）
        result.sort((a, b) -> {
            int sortA = a.getSortOrder() != null ? a.getSortOrder() : 0;
            int sortB = b.getSortOrder() != null ? b.getSortOrder() : 0;
            return Integer.compare(sortB, sortA);
        });

        return result;
    }

    /**
     * 将 Epoch 分段或兜底字符串注入到消息列表中 System prompt 之后。
     * <p>
     * Phase 4: 优先注入 Epoch 分段（各段独立 System 消息，支持缓存感知），
     * 无分段时降级到单条压缩摘要（Phase 2 向后兼容）。
     * 配合 O2 缓存优化：基座 SYSTEM 保持纯静态，Epoch 上下文作为独立 USER 消息。
     */
    private static void insertContextMessages(List<Msg> msgs, ContextResult epochResult, String fallbackContext) {
        int insertIdx = 1; // 第一条 SYSTEM 消息之后（O2: 此处已无动态上下文污染 SYSTEM 基座）

        if (epochResult != null && !epochResult.isEmpty()) {
            for (ContextSegment seg : epochResult.getSegments()) {
                if (seg.hasContent()) {
                    msgs.add(insertIdx++, Msg.builder()
                            .role(Role.USER)
                            .text(seg.getContent())
                            .build());
                }
            }
        } else if (fallbackContext != null && !fallbackContext.isBlank()) {
            msgs.add(insertIdx, Msg.of(Role.USER, ChatPrompts.contextInjection(fallbackContext).content()));
        }
    }

    /**
     * 计算消息列表的 token 数（含 system prompt 基数）。
     */
    private int estimateMessagesTokens(List<Message> msgs, String systemPromptBase) {
        int total = estimateTokens(systemPromptBase);
        for (Message msg : msgs) {
            total += estimateMessageTokens(msg);
        }
        return total;
    }

    /**
     * 估算单条消息的 token 数。
     */
    private static int estimateMessageTokens(Message msg) {
        int tokens = estimateTokens(msg.getContent());
        if (msg.getAttachments() != null) {
            tokens += estimateTokens(msg.getAttachments());
        }
        return tokens;
    }

    /**
     * 粗略估算文本的 token 数。
     */
    private static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        return (int) Math.ceil(text.length() / 2.0);
    }

    /**
     * 从消息列表中查找最后一条非排除的用户消息 ID。
     * 用于精确指定图片和 RAG 上下文的注入目标，修复「最后一条消息」脆弱判断。
     */
    private static UUID findTargetMessageId(List<Message> messages, UUID excludeMessageId) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message message = messages.get(i);
            if (message.isUser() && !message.getMessageId().equals(excludeMessageId)) {
                return message.getMessageId();
            }
        }
        return null;
    }

    /**
     * 构建 RAG 上下文：根据对话绑定的知识库 ID 搜索相关内容，经 Reranker 重排序后格式化返回。
     * 未绑定知识库、搜索无结果或服务异常时返回 null，不阻塞主流程。
     */
    private String buildRagContext(String query, Conversation conversation, UUID accountId) {
        return ragContextBuilder.build(query, conversation, accountId);
    }

    /**
     * 解析模型 context length，未配置时默认 4096
     */
    private int resolveContextLength(UUID providerId, UUID accountId) {
        try {
            LlmProvider provider = providerId != null ? modelRouter.getProvider(providerId) : modelRouter.getDefaultProvider(accountId);
            Integer cl = provider.getContextLength();
            return cl != null && cl > 0 ? cl : 4096;
        } catch (Exception e) {
            return 4096;
        }
    }

    /**
     * 根据模型能力动态计算 prompt 预算占上下文窗口的比例。
     * Thinking 模型需要更多输出空间 → 降低 prompt 比例；
     * 标准模型使用常规比例。
     */
    private double resolveBudgetRatio(UUID providerId, UUID accountId) {
        try {
            LlmProvider provider = providerId != null
                    ? modelRouter.getProvider(providerId)
                    : modelRouter.getDefaultProvider(accountId);
            String modelName = provider.getPrimaryModelName();
            if (modelName == null || modelName.isBlank()) return 0.5;
            var caps = modelCapabilityRegistry.infer(modelName, provider.getProviderType());
            if (caps.contains(ModelCapability.THINKING)) return 0.4;
            if (caps.contains(ModelCapability.REASONING_EFFORT)) return 0.45;
            return 0.5;
        } catch (Exception e) {
            return 0.5;
        }
    }

    /**
     * 使用默认 provider 的 budget 比例（无 providerId 时使用账户默认模型）。
     */
    private double resolveBudgetRatio(UUID accountId) {
        return resolveBudgetRatio(null, accountId);
    }

    /**
     * CHAT 工具决议：优先 MCP 连接 → MCP 全部失败时语义匹配降级。
     * <p>
     * 与 O2 缓存优化配合：此方法返回动态上下文消息列表（用户画像/情境闪现/目标/文件快照/项目提示），
     * 替代旧版「整个 system prompt 替换」模式。SYSTEM 消息保持纯静态 CHAT_SYSTEM 基座。
     */
    private static class ChatToolResult {
        final List<ToolSpec> tools;
        final List<Msg> contextMsgs;

        ChatToolResult(List<ToolSpec> tools, List<Msg> contextMsgs) {
            this.tools = tools;
            this.contextMsgs = contextMsgs;
        }
    }

    private ChatToolResult resolveChatTools(Conversation conversation, String userContent, String toolConvContext, UUID accountId) {
        String projectHint = buildProjectHint(conversation, accountId);

        // 构建动态上下文段落（均为独立 USER 消息，不污染 SYSTEM 前缀）
        String userProfileSection = buildUserProfileSection(accountId, userContent);
        String flashbackSection = buildFlashbackSection(accountId, userContent);
        String goalSection = buildGoalContext(conversation.getConversationId(), accountId);
        String fileSnapshot = buildFileSnapshot(accountId, conversation.getProjectId());

        // 合并为一条上下文 USER 消息
        List<Msg> contextMsgs = buildDynamicContextMsgs(userProfileSection, flashbackSection, goalSection, fileSnapshot, projectHint);

        // 合并工具描述后备文本（用于语义降级路径追加到 contextMsgs 末尾）
        StringBuilder toolTextSb = new StringBuilder();

        // 始终获取内部注册的工具（bash）
        List<ToolSpec> internalTools = getInternalTools();
        log.info("CHAT 工具解析: internalTools={} mcpServers={}", internalTools.stream().map(ToolSpec::name).toList(), conversation.getMcpServerIds());
        List<String> enabledServers = conversation.getMcpServerIds();
        if (enabledServers != null && !enabledServers.isEmpty()) {
            List<ToolSpec> mcpTools = resolveMcpTools(conversation);
            if (mcpTools != null) {
                List<ToolSpec> merged = new ArrayList<>(mcpTools);
                merged.addAll(internalTools);
                log.info("CHAT 工具解析: 合并模式 MCP={} 内部={}", mcpTools.size(), internalTools.size());
                return new ChatToolResult(merged, contextMsgs);
            }
            // MCP 全部连接失败 → 语义匹配降级
            try {
                MatchedTools matched = toolAutoResolver.resolveForChat(userContent, toolConvContext, accountId);
                if (!matched.isEmpty()) {
                    recordToolMatchLogs(conversation, matched, accountId);
                    var semanticTools = new ArrayList<>(toolEnricher.toSchemas(matched));
                    semanticTools.addAll(internalTools);
                    toolTextSb.append(toolEnricher.enrichPrompt("", matched.metas()));
                    log.info("CHAT 工具解析: 语义降级模式 tools={}", semanticTools.stream().map(ToolSpec::name).toList());
                    // 工具描述追加到上下文消息中
                    if (!toolTextSb.isEmpty()) {
                        contextMsgs = appendToolTextToContextMsgs(contextMsgs, toolTextSb.toString());
                    }
                    return new ChatToolResult(semanticTools, contextMsgs);
                }
            } catch (Exception e) {
                log.warn("CHAT 工具语义匹配降级失败: {}", e.getMessage());
            }
        }
        // 无 MCP 服务器 → 仅返回内部工具
        if (internalTools.isEmpty()) {
            log.info("CHAT 工具解析: 无可用工具");
            return new ChatToolResult(null, contextMsgs);
        }
        log.info("CHAT 工具解析: 内部工具模式 tools={}", internalTools.stream().map(ToolSpec::name).toList());
        return new ChatToolResult(internalTools, contextMsgs);
    }

    /**
     * 构建动态上下文 USER 消息列表。将用户画像、情境闪现、目标、文件快照、项目提示
     * 合并为一条 USER 消息（加一条 ASSISTANT 确认），不污染 SYSTEM 基座的缓存前缀。
     */
    private static List<Msg> buildDynamicContextMsgs(String userProfileSection, String flashbackSection,
                                                      String goalSection, String fileSnapshot,
                                                      String projectHint) {
        StringBuilder ctxSb = new StringBuilder();
        if (userProfileSection != null) ctxSb.append("\n").append(userProfileSection);
        if (flashbackSection != null) ctxSb.append("\n\n").append(flashbackSection);
        if (goalSection != null) ctxSb.append("\n\n").append(goalSection);
        if (fileSnapshot != null) ctxSb.append("\n\n").append(fileSnapshot);
        if (projectHint != null) ctxSb.append("\n\n").append(projectHint);

        if (ctxSb.length() == 0) return List.of();
        return List.of(Msg.of(Role.USER, ctxSb.toString().strip()));
    }

    /** 在上下文消息末尾追加工具描述文本。 */
    private static List<Msg> appendToolTextToContextMsgs(List<Msg> contextMsgs, String toolText) {
        if (toolText == null || toolText.isBlank()) return contextMsgs;
        List<Msg> result = new ArrayList<>(contextMsgs);
        int lastIdx = result.size() - 1;
        if (lastIdx >= 0) {
            Msg last = result.get(lastIdx);
            String combined = last.text() + "\n\n" + toolText;
            result.set(lastIdx, Msg.of(Role.USER, combined));
        } else {
            result.add(Msg.of(Role.USER, toolText));
        }
        return result;
    }

    /**
     * 获取内部注册的工具（bash + 文件操作工具）。
     */
    private List<ToolSpec> getInternalTools() {
        Set<String> allowed = Set.of("bash", "file_read", "file_write", "file_list", "file_search");
        return toolRegistry.allSpecs().stream()
                .filter(s -> allowed.contains(s.name()))
                .toList();
    }

    /**
     * 项目文件上下文（一次 DB 查询解析全部所需信息）。
     */
    private record ProjectFileContext(String hint, String fileRootPath, boolean archived) {
    }

    /**
     * 构建项目文件路径上下文提示词片段。
     */
    private String buildProjectHint(Conversation conversation, UUID accountId) {
        ProjectFileContext ctx = resolveProjectFileContext(conversation, accountId);
        return ctx != null ? ctx.hint() : null;
    }

    /**
     * 情境闪现：在低保留率记忆中寻找高相关度的条目，格式化为"回忆闪现"段落。
     * 嵌入到 system prompt 中，让 AI 在回复时感知到用户相关历史记忆。
     * 无闪现候选项或异常时返回 null，不阻塞主流程。
     */
    private String buildFlashbackSection(UUID accountId, String context) {
        try {
            var candidates = flashbackScanner.scan(accountId, context, 5);
            if (candidates == null || candidates.isEmpty()) return null;

            StringBuilder sb = new StringBuilder("## 回忆闪现\n");
            sb.append("以下是与当前话题相关的过往记忆，请留意它们是否对当前对话有帮助：\n");
            int count = 0;
            for (var c : candidates) {
                if (c.getRelevanceScore() <= 0) continue;
                String content = c.getContent();
                if (content == null || content.isBlank()) continue;
                String line = content.length() > 150 ? content.substring(0, 147) + "..." : content;
                String level = switch (c.getLevel()) {
                    case SESSION -> "会话";
                    case USER -> "长期";
                    case TEAM -> "团队";
                    case PROJECT -> "项目";
                };
                sb.append("- [").append(level).append("] ").append(line).append("\n");
                count++;
            }
            if (count == 0) return null;
            return sb.toString();
        } catch (Exception e) {
            log.debug("情境闪现构建失败(不影响主流程): {}", e.getMessage());
            return null;
        }
    }

    /**
     * 构建当前执行目标上下文。查询对话关联的 Goal，格式化为进度注入到 system prompt。
     * 让 LLM 在 CHAT 模式下也能感知当前任务进度，知道下一步该做什么。
     */
    private String buildGoalContext(UUID conversationId, UUID accountId) {
        try {
            Goal goal = goalRepository.findByConversationId(conversationId).orElse(null);
            if (goal == null || goal.isCompleted() || goal.getMilestones() == null || goal.getMilestones().isEmpty()) {
                return null;
            }
            StringBuilder sb = new StringBuilder("## 当前执行目标\n");
            sb.append("目标：").append(goal.getTitle()).append("（")
              .append(goal.getStatus()).append("）\n");
            sb.append("进度：").append(goal.getCompletedTasks()).append("/")
              .append(goal.getTotalTasks()).append(" 任务完成\n\n");

            for (Milestone m : goal.getMilestones()) {
                boolean allDone = m.getTasks() != null && !m.getTasks().isEmpty()
                        && m.getTasks().stream().allMatch(t -> t.isCompleted());
                boolean hasDone = m.getTasks() != null && m.getTasks().stream().anyMatch(t -> t.isCompleted());
                String statusMark = allDone ? "✅" : (hasDone ? "🔄" : "⏳");
                sb.append(statusMark).append(" ").append(m.getName()).append("（");
                long doneCount = m.getTasks() != null ? m.getTasks().stream().filter(t -> t.isCompleted()).count() : 0;
                long totalCount = m.getTasks() != null ? m.getTasks().size() : 0;
                sb.append(doneCount).append("/").append(totalCount).append(" 任务）\n");
                if (m.getTasks() != null) {
                    for (Task t : m.getTasks()) {
                        sb.append(t.isCompleted() ? "  ✅ " : "  ⏳ ");
                        sb.append(t.getDescription()).append("\n");
                    }
                }
            }
            return sb.toString();
        } catch (Exception e) {
            log.debug("构建执行目标上下文失败(不影响主流程): {}", e.getMessage());
            return null;
        }
    }

    /**
     * 构建工作目录状态快照：文件数、目录数、最近修改文件。
     * 让 LLM 知道当前工作目录有什么，避免频繁 file_list。
     */
    private String buildFileSnapshot(UUID accountId, UUID projectId) {
        if (projectId == null) return null;
        try {
            String rootPath = groupService.getProjectRootPath(accountId, projectId);
            if (rootPath == null) return null;
            Path root = Paths.get(rootPath);
            if (!Files.exists(root)) return null;

            List<Path> recentFiles = new ArrayList<>();
            int fileCount = 0;
            int dirCount = 0;

            try (java.util.stream.Stream<java.nio.file.Path> stream = java.nio.file.Files.walk(root, 3)) {
                java.util.Iterator<java.nio.file.Path> it = stream.iterator();
                while (it.hasNext()) {
                    java.nio.file.Path p = it.next();
                    if (p.equals(root)) continue;
                    if (java.nio.file.Files.isDirectory(p)) {
                        dirCount++;
                    } else if (java.nio.file.Files.isRegularFile(p)) {
                        fileCount++;
                        recentFiles.add(p);
                    }
                }
            }

            // 按最后修改时间排序，取最新 5 个
            recentFiles.sort((a, b) -> {
                try {
                    long ta = java.nio.file.Files.getLastModifiedTime(a).toMillis();
                    long tb = java.nio.file.Files.getLastModifiedTime(b).toMillis();
                    return Long.compare(tb, ta);
                } catch (java.io.IOException e) { return 0; }
            });
            if (recentFiles.size() > 5) recentFiles = recentFiles.subList(0, 5);

            StringBuilder sb = new StringBuilder("## 工作目录状态\n");
            sb.append("路径：").append(rootPath).append("\n");
            sb.append("文件：").append(fileCount).append(" 个文件，").append(dirCount).append(" 个目录\n");
            if (!recentFiles.isEmpty()) {
                sb.append("最近修改：\n");
                long now = System.currentTimeMillis();
                for (Path f : recentFiles) {
                    String rel = root.relativize(f).toString();
                    long lastMod = Files.getLastModifiedTime(f).toMillis();
                    long diffMin = (now - lastMod) / 60000;
                    String timeAgo = diffMin < 1 ? "刚刚" : diffMin + " 分钟前";
                    sb.append("  ").append(rel).append("（").append(timeAgo).append("）\n");
                }
            }
            return sb.toString();
        } catch (Exception e) {
            log.debug("构建文件状态快照失败(不影响主流程): {}", e.getMessage());
            return null;
        }
    }

    /**
     * 解析项目文件上下文：提示词 + 根目录路径 + 归档状态。一次 DB 查询。
     */
    private ProjectFileContext resolveProjectFileContext(Conversation conversation, UUID accountId) {
        UUID projectId = conversation.getProjectId();
        if (projectId == null) {
            log.warn("对话缺少 projectId，无法解析文件上下文: conversationId={}", conversation.getConversationId());
            return null;
        }
        try {
            var project = groupService.findOwned(accountId, projectId);
            String projectPath = project.getLocalPath();
            String hint = null;
            if (projectPath != null && !projectPath.isBlank()) {
                String sharedPath = Paths.get(fileRootPath).resolve("shared").toString();
                hint = ChatPrompts.projectContextHint(project.getName(), projectPath, sharedPath).content();
            }
            String frp = groupService.getProjectRootPath(accountId, projectId);
            return new ProjectFileContext(hint, frp, Boolean.TRUE.equals(project.getArchived()));
        } catch (Exception e) {
            log.warn("解析项目文件上下文失败: accountId={}, projectId={}", accountId, projectId, e);
            return null;
        }
    }

    /**
     * 记录工具匹配分数到 tool_match_logs 表，同时统计匹配的技能使用次数。
     */
    private void recordToolMatchLogs(Conversation conversation, MatchedTools matched, UUID accountId) {
        try {
            Map<String, Double> scores = matched.toolScores();
            if (scores == null || scores.isEmpty()) return;
            double threshold = matched.threshold();
            List<ToolMatchLog> logs = new ArrayList<>();
            for (var entry : scores.entrySet()) {
                String toolName = entry.getKey();
                Double sim = entry.getValue();
                Map<String, String> toolServerIds = matched.toolServerIds();
                String serverId = toolServerIds != null
                        ? toolServerIds.getOrDefault(toolName, "")
                        : "";
                logs.add(ToolMatchLog.builder()
                        .accountId(accountId)
                        .conversationId(conversation.getConversationId())
                        .toolName(toolName)
                        .serverId(serverId)
                        .similarity(sim)
                        .threshold(threshold)
                        .passed(sim >= threshold)
                        .build());
            }
            toolMatchLogRepository.saveAll(logs);

            // 统计技能使用次数：对通过阈值的匹配项，查找对应技能并递增 usageCount
            for (var entry : scores.entrySet()) {
                if (entry.getValue() >= threshold) {
                    String toolName = entry.getKey();
                    try {
                        skillRepository.findByAccountAndName(accountId, toolName)
                                .ifPresent(skill -> {
                                    skill.recordUsage();
                                    skillRepository.save(skill);
                                });
                    } catch (Exception ignored) {
                        // 非技能工具（如 MCP 工具）静默跳过
                    }
                }
            }
        } catch (Exception e) {
            log.warn("记录工具匹配分数失败: {}", e.getMessage());
        }
    }

    /**
     * 加载对话配置的 MCP 工具：连接指定的 MCP 服务器，返回工具 spec 列表。
     * 自愈已删除的服务器引用。无法连接时返回 null，不阻塞主流程。
     */
    private List<ToolSpec> resolveMcpTools(Conversation conversation) {
        List<String> enabledServers = conversation.getMcpServerIds();
        if (enabledServers == null || enabledServers.isEmpty()) return null;
        boolean anyConnected = false;
        List<String> validServers = new ArrayList<>();
        for (String serverId : enabledServers) {
            try {
                UUID sid = UUID.fromString(serverId);
                if (mcpConnectionManager.isConnected(sid)) {
                    validServers.add(serverId);
                    anyConnected = true;
                } else {
                    mcpConnectionManager.connectIfActive(sid);
                }
            } catch (IllegalArgumentException e) {
                log.warn("MCP 服务器已被删除，从对话中移除: serverId={}", serverId);
            }
        }
        if (validServers.size() < enabledServers.size()) {
            conversation.setMcpServerIds(validServers.isEmpty() ? null : validServers);
            conversationRepository.update(conversation);
        }
        if (!anyConnected) return null;
        return toolRegistry.allSpecs().stream().map(s -> new ToolSpec(s.name(), s.description(), s.inputSchema())).collect(java.util.stream.Collectors.toList());
    }

    private Conversation findOwned(UUID accountId, UUID conversationId) {
        return OwnershipValidator.findOwned(accountId, "对话", conversationId, conversationRepository::findById, Conversation::getAccountId);
    }

    /**
     * 对话实体转为响应对象。
     */
    private ConversationResponse toResponse(Conversation conversation) {
        return ConversationResponse.builder().conversationId(conversation.getConversationId()).projectId(conversation.getProjectId()).title(conversation.getTitle()).messageCount(conversation.getMessageCount()).knowledgeBaseIds(conversation.getKnowledgeBaseIds()).mcpServerIds(conversation.getMcpServerIds()).lastMessageAt(conversation.getLastMessageAt()).createdAt(conversation.getCreatedAt()).updatedAt(conversation.getUpdatedAt()).build();
    }

    /**
     * 消息实体转为响应对象。
     */
    private MessageResponse toMessageResponse(Message message) {
        return toMessageResponse(message, null, java.util.Collections.emptyMap());
    }

    /**
     * 消息实体转为响应对象（含预查的 generationTotal，避免 N+1）。
     */
    private MessageResponse toMessageResponse(Message message, Integer preloadedGenTotal) {
        return toMessageResponse(message, preloadedGenTotal, java.util.Collections.emptyMap());
    }

    /**
     * 消息实体转为响应对象（含预查的 generationTotal 和 replyTo，避免 N+1）。
     */
    private MessageResponse toMessageResponse(Message message, Integer preloadedGenTotal, Map<UUID, Message> replyToMap) {
        List<String> rawImages = MessageAttachmentsSerializer.deserializeImages(message.getImages());
        List<String> resolvedImages = rawImages != null ? resolveAttachmentUrls(rawImages) : null;
        List<String> rawAudios = MessageAttachmentsSerializer.deserializeAudios(message.getAudios());
        List<String> resolvedAudios = rawAudios != null ? resolveAttachmentUrls(rawAudios) : null;
        Integer genTotal = preloadedGenTotal != null ? preloadedGenTotal : (message.getGenerationGroup() != null ? messageRepository.countByGenerationGroup(message.getGenerationGroup()) : null);
        // 解析被引用消息
        Map<String, String> replyTo = null;
        if (message.getReplyToId() != null) {
            Message ref = replyToMap.get(message.getReplyToId());
            if (ref != null) {
                replyTo = Map.of("role", ref.getRole(), "content", ref.getContent());
            }
        }
        return MessageResponse.builder().messageId(message.getMessageId()).conversationId(message.getConversationId()).projectId(message.getProjectId()).role(message.getRole()).content(message.getContent()).contentType(message.getContentType()).thinking(message.getThinking()).targetAgent(message.getTargetAgent()).replyToId(message.getReplyToId()).replyTo(replyTo).sortOrder(message.getSortOrder()).status(message.getStatus() != null ? message.getStatus().name() : null).rating(message.getRating()).model(message.getModel()).totalTokens(message.getTotalTokens()).durationMs(message.getDurationMs()).thinkingDurationMs(message.getThinkingDurationMs()).thinkingTokens(message.getThinkingTokens()).chain(message.getChain()).generationIndex(message.getGenerationIndex()).generationGroup(message.getGenerationGroup()).generationTotal(genTotal).images(resolvedImages).audios(resolvedAudios).attachments(MessageAttachmentsSerializer.deserializeAttachments(message.getAttachments())).createdAt(message.getCreatedAt()).build();
    }

    /**
     * 将附件引用列表中的 fileId（UUID）转为 API 下载 URL，base64 data URI 原样保留。
     */
    private static List<String> resolveAttachmentUrls(List<String> uris) {
        if (uris == null) return null;
        List<String> result = new ArrayList<>(uris.size());
        for (String uri : uris) {
            if (uri == null || uri.startsWith("data:")) {
                result.add(uri);
            } else {
                try {
                    UUID.fromString(uri);
                    result.add("/api/files/" + uri);
                } catch (IllegalArgumentException e) {
                    result.add(uri);
                }
            }
        }
        return result;
    }

    /**
     * 优先读快照，无快照时检查是否有进行中的异步压缩。
     * 有则等待异步压缩完成（最多 5s），避免重复压缩；否则执行同步压缩。
     */
    private Mono<CompressResult> resolveCompressResult(Conversation conversation, UUID conversationId,
                                                       int historyBudget, UUID accountId,
                                                       Consumer<String> progress, String currentQuery) {
        return resolveCompressResult(conversation, conversationId, historyBudget, accountId, progress, currentQuery, null);
    }

    /**
     * 优先读快照，无快照时检查是否有进行中的异步压缩（支持预加载消息列表）。
     *
     * @param allMessages 可选预加载消息列表，非 null 时同步压缩使用该列表避免重复查询
     */
    private Mono<CompressResult> resolveCompressResult(Conversation conversation, UUID conversationId,
                                                       int historyBudget, UUID accountId,
                                                       Consumer<String> progress, String currentQuery,
                                                       List<Message> allMessages) {
        String cached = conversation.getCompressedContext();
        if (cached != null && !cached.isBlank()) {
            log.debug("使用延迟压缩快照: conversationId={}, contextLen={}", conversationId, cached.length());
            return Mono.just(new CompressResult(cached, List.of(), List.of(), conversation.getCompressedUpToMsgId(), true));
        }

        // 检查是否有进行中的异步压缩，等待其完成
        CompletableFuture<String> pending = compressionFutures.get(conversationId);
        if (pending != null && !pending.isDone()) {
            log.debug("等待异步压缩完成: conversationId={}", conversationId);
            return Mono.fromFuture(pending)
                    .flatMap(summary -> {
                        if (summary != null && !summary.isBlank()) {
                            return Mono.just(new CompressResult(summary, List.of(), List.of(),
                                    conversation.getCompressedUpToMsgId(), true));
                        }
                        // 异步压缩失败或结果为空，执行同步压缩
                        return syncCompress(conversationId, historyBudget, currentQuery, allMessages);
                    })
                    .timeout(java.time.Duration.ofSeconds(5))
                    .onErrorResume(e -> {
                        log.warn("等待异步压缩超时/异常，执行同步压缩: conversationId={}", conversationId, e);
                        return syncCompress(conversationId, historyBudget, currentQuery, allMessages);
                    });
        }

        // 结构感知提取压缩：根据 contextLength 折算的 budget 自适应策略，零 LLM
        return syncCompress(conversationId, historyBudget, currentQuery, allMessages);
    }

    /** 使用预加载消息或从 repository 加载执行同步压缩。 */
    private Mono<CompressResult> syncCompress(UUID conversationId, int historyBudget, String currentQuery,
                                               List<Message> allMessages) {
        if (allMessages != null) {
            return conversationCompressor.compress(allMessages, historyBudget, currentQuery);
        }
        return conversationCompressor.compress(conversationId, historyBudget, currentQuery);
    }

    /**
     * 异步触发压缩，结果写入 Conversation 快照。不阻塞当前请求。
     * 同时将 CompletableFuture 存入 compressionFutures，
     * 供后续 resolveCompressResult 等待异步结果。
     */
    private void scheduleAsyncCompression(UUID conversationId, UUID accountId, UUID upToMsgId) {
        int contextLength = resolveContextLength(null, accountId);
        int historyBudget = (int) (contextLength * resolveBudgetRatio(accountId) * 0.85);
        Consumer<String> progress = msg -> log.debug("异步压缩: {}", msg);

        CompletableFuture<String> future = new CompletableFuture<>();
        compressionFutures.put(conversationId, future);
        // 无论成功或异常，完成后清理 Map 条目防止泄漏
        future.whenComplete((r, e) -> compressionFutures.remove(conversationId));

        Mono.defer(() -> {
            Conversation conv = conversationRepository.findById(conversationId).orElse(null);
            if (conv == null) return Mono.empty();
            conv.setCompressedUpToMsgId(upToMsgId);
            conversationRepository.update(conv);
            return Mono.just(conv);
        }).flatMap(conv ->
                historyCompressor.compressStream(conversationId, historyBudget, accountId, progress)
                        .flatMap(result -> {
                            String summary = result.toSummaryText();
                            if (summary != null && !summary.isBlank()) {
                                conv.setCompressedContext(summary);
                                conversationRepository.update(conv);
                                future.complete(summary);
                            } else {
                                future.complete("");
                            }
                            return Mono.empty();
                        })
        ).onErrorResume(e -> {
            log.warn("异步压缩失败: conversationId={}, {}", conversationId, e.getMessage());
            future.completeExceptionally(e);
            return Mono.empty();
        }).subscribeOn(Schedulers.boundedElastic()).subscribe(null, err -> {
            log.warn("异步压缩异常: {}", err.getMessage());
            future.completeExceptionally(err);
        });
    }

    /**
     * 将异常转换为用户友好的错误消息（禁止暴露底层 SQL/框架细节）。
     */
    private static String toUserMessage(String fallbackPrefix, Throwable e) {
        if (e == null) return fallbackPrefix + "：未知错误";
        if (e instanceof DataIntegrityViolationException) {
            return fallbackPrefix + "：数据冲突，请稍后重试";
        }
        String msg = e.getMessage();
        if (msg == null) return fallbackPrefix + "：未知错误";
        // SQL / 批量操作异常
        if (msg.contains("could not execute batch") || msg.contains("Batch entry")
                || msg.contains("could not execute statement") || msg.contains("SQL")
                || msg.contains("constraint") || msg.contains("violation")) {
            return fallbackPrefix + "：数据异常，请稍后重试";
        }
        // 网络 / 连接异常（可能暴露内部地址）
        if (msg.contains("Connection refused") || msg.contains("connect timed out")
                || msg.contains("Failed to connect") || msg.contains("Host")
                || e instanceof java.net.SocketTimeoutException
                || e instanceof java.net.ConnectException) {
            return fallbackPrefix + "：服务暂时不可用，请稍后重试";
        }
        // HTTP 客户端异常（可能泄露 API Key）
        if (msg.contains("401") || msg.contains("403") || msg.contains("Unauthorized")
                || msg.contains("Forbidden") || msg.contains("API key")
                || msg.contains("api_key") || msg.contains("Bearer")) {
            return fallbackPrefix + "：服务认证失败，请检查配置";
        }
        return fallbackPrefix + "：服务异常，请稍后重试";
    }
}
