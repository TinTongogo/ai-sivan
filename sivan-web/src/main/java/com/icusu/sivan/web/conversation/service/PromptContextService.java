package com.icusu.sivan.web.conversation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.icusu.sivan.agent.mcp.McpConnectionManager;
import com.icusu.sivan.agent.model.ModelCapabilityRegistry;
import com.icusu.sivan.agent.model.ModelRouter;
import com.icusu.sivan.agent.prompt.ChatPrompts;
import com.icusu.sivan.agent.tool.MatchedTools;
import com.icusu.sivan.agent.tool.ToolEnricher;
import com.icusu.sivan.agent.tool.ToolRegistryImpl;
import com.icusu.sivan.agent.tool.ToolResolver;
import com.icusu.sivan.core.message.Msg;
import com.icusu.sivan.core.message.Role;
import com.icusu.sivan.core.tool.ToolSpec;
import com.icusu.sivan.domain.account.IAccountRepository;
import com.icusu.sivan.domain.account.IUserProfileRepository;
import com.icusu.sivan.domain.account.UserProfile;
import com.icusu.sivan.domain.context.ContextSegment;
import com.icusu.sivan.domain.conversation.*;
import com.icusu.sivan.domain.file.FileStoragePort;
import com.icusu.sivan.domain.model.LlmProvider;
import com.icusu.sivan.domain.model.ModelCapability;
import com.icusu.sivan.domain.shared.port.IEmbeddingService;
import com.icusu.sivan.common.util.CosineSimilarity;
import com.icusu.sivan.infra.file.DocumentTextExtractor;
import com.icusu.sivan.infra.memory.flashback.FlashbackScanner;
import com.icusu.sivan.web.conversation.dto.SendMessageRequest;
import com.icusu.sivan.web.conversation.service.message.*;
import com.icusu.sivan.web.conversation.service.tree.ContextBuilder;
import com.icusu.sivan.web.conversation.service.tree.ContextCache;
import com.icusu.sivan.web.conversation.service.tree.ContextResult;
import com.icusu.sivan.web.knowledge.service.RagContextBuilder;
import com.icusu.sivan.web.service.GroupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Stream;

/**
 * Prompt 上下文服务 — 构建 LLM 调用所需的完整上下文，包括消息构建、工具解析、文件操作。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PromptContextService {

    private final ModelRouter modelRouter;
    private final FileStoragePort fileStorageService;
    private final RagContextBuilder ragContextBuilder;
    private final ContextBuilder contextBuilder;
    private final ContextCache contextCache;
    private final FlashbackScanner flashbackScanner;
    private final IUserProfileRepository userProfileRepository;
    private final IAccountRepository accountRepository;
    private final IEmbeddingService embeddingService;
    private final GroupService groupService;
    private final ModelCapabilityRegistry modelCapabilityRegistry;
    private final DocumentTextExtractor documentTextExtractor;
    private final ToolRegistryImpl toolRegistry;
    private final ToolResolver toolAutoResolver;
    private final ToolEnricher toolEnricher;
    private final McpConnectionManager mcpConnectionManager;
    private final IMessageRepository messageRepository;
    private final IConversationRepository conversationRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public static final int MAX_MESSAGES_LOAD = 500;

    // =====================================================================
    // 上下文构建
    // =====================================================================

    public String buildEpochContext(UUID conversationId, CompressResult compressResult,
                                    int historyBudget, List<Message> allMessages) {
        try {
            ContextResult epochResult = contextBuilder.buildEpochs(
                    conversationId, compressResult, historyBudget, allMessages);
            epochResult = contextCache.apply(conversationId, epochResult);
            if (epochResult != null && !epochResult.isEmpty()) {
                return epochResult.toFlatString();
            }
        } catch (Exception e) {
            log.warn("ContextBuilder 增强失败，降级到压缩摘要", e);
        }
        return compressResult.toSummaryText();
    }

    /**
     * 构建 Epoch 分段上下文，返回结构化的 {@link ContextResult}。
     * 调用方可将各 segment 独立注入 LLM 消息，利用 prompt caching 降低成本。
     */
    public ContextResult buildEpochContextResult(UUID conversationId, CompressResult compressResult,
                                                 int historyBudget, List<Message> allMessages) {
        ContextResult epochResult = allMessages != null
                ? contextBuilder.buildEpochs(conversationId, compressResult, historyBudget, allMessages)
                : contextBuilder.buildEpochs(conversationId, compressResult, historyBudget);
        epochResult = contextCache.apply(conversationId, epochResult);
        return epochResult;
    }

    public List<Msg> buildLlmMessages(UUID conversationId, UUID accountId, List<String> images, List<String> audios,
                                      int contextLength, UUID excludeMessageId, String ragContext,
                                      String compressedContext, Set<UUID> protectMsgIds, UUID providerId) {
        List<EnrichedMessage> enriched = enrichMessages(conversationId, images, audios, contextLength,
                excludeMessageId, ragContext, protectMsgIds, accountId, providerId);

        CoreMessageBuilder coreBuilder = new CoreMessageBuilder(fileStorageService);
        List<Msg> msgs = coreBuilder.build(ChatPrompts.CHAT_SYSTEM.content(), enriched, excludeMessageId, accountId);

        if (compressedContext != null && !compressedContext.isBlank()) {
            msgs.add(1, Msg.of(Role.USER, ChatPrompts.contextInjection(compressedContext).content()));
        }
        return msgs;
    }

    public List<EnrichedMessage> enrichMessages(UUID conversationId, List<String> images, List<String> audios,
                                                int contextLength, UUID excludeMessageId, String ragContext,
                                                Set<UUID> protectMsgIds, UUID accountId, UUID providerId) {
        List<Message> messages = new ArrayList<>(
                messageRepository.findLatestByConversationId(conversationId, MAX_MESSAGES_LOAD));

        // 排除与 excludeMessageId 同 generationGroup 的消息（重新生成时不携带旧版回复）
        if (excludeMessageId != null) {
            messageRepository.findById(excludeMessageId).ifPresent(excluded -> {
                UUID genGroup = excluded.getGenerationGroup();
                if (genGroup != null) {
                    messages.removeIf(m -> genGroup.equals(m.getGenerationGroup()));
                }
            });
        }

        Collections.reverse(messages);
        int maxPromptTokens = (int) (contextLength * resolveBudgetRatio(providerId, accountId));
        List<Message> truncated = truncateWithProtection(messages, ChatPrompts.CHAT_SYSTEM.content(),
                maxPromptTokens, protectMsgIds != null ? protectMsgIds : Collections.emptySet());
        Collections.reverse(truncated);

        UUID targetMessageId = findTargetMessageId(truncated, excludeMessageId);
        boolean visionSupported = isVisionSupported(providerId, accountId);

        ContentEnricherPipeline pipeline = new ContentEnricherPipeline()
                .addEnricher(new FileAttachmentEnricher())
                .addEnricher(new DocumentAttachmentEnricher(documentTextExtractor, fileStorageService))
                .addEnricher(new ReplyQuoteEnricher(messageRepository))
                .addEnricher(new RagContextEnricher(ragContext, targetMessageId));
        if (visionSupported) {
            pipeline.addEnricher(new ImageAttachmentEnricher(images, targetMessageId));
        }
        if (isAudioSupported(providerId, accountId)) {
            pipeline.addEnricher(new AudioAttachmentEnricher(audios, targetMessageId));
        }
        return pipeline.enrich(truncated, messages);
    }

    public boolean isVisionSupported(UUID providerId, UUID accountId) {
        try {
            var provider = providerId != null
                    ? modelRouter.getProvider(providerId)
                    : modelRouter.getDefaultProvider(accountId);
            return provider != null && provider.supportsCapability("vision");
        } catch (Exception e) {
            return false;
        }
    }

    public boolean isAudioSupported(UUID providerId, UUID accountId) {
        try {
            var provider = providerId != null
                    ? modelRouter.getProvider(providerId)
                    : modelRouter.getDefaultProvider(accountId);
            return provider != null && provider.supportsCapability("audio");
        } catch (Exception e) {
            return false;
        }
    }

    public List<Message> truncateWithProtection(List<Message> messages, String systemPrompt,
                                                int maxPromptTokens, Set<UUID> protectMsgIds) {
        int baseTokens = estimateTokens(systemPrompt);

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

        result.sort((a, b) -> {
            int sortA = a.getSortOrder() != null ? a.getSortOrder() : 0;
            int sortB = b.getSortOrder() != null ? b.getSortOrder() : 0;
            return Integer.compare(sortB, sortA);
        });

        return result;
    }

    public static void insertContextMessages(List<Msg> msgs, ContextResult epochResult, String fallbackContext) {
        int insertIdx = 1;
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

    // =====================================================================
    // 用户画像
    // =====================================================================

    public String buildUserProfileSection(UUID accountId) {
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

    public String buildUserProfileSection(UUID accountId, String queryContext) {
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

            // 批量 embedding 所有标签，避免逐调引发 N 次 HTTP 请求
            List<String> allTags = profile.getExpertise();
            List<float[]> tagVecs = embeddingService.embedBatch(allTags);

            var scored = new ArrayList<ScoredTag>();
            for (int i = 0; i < allTags.size() && i < tagVecs.size(); i++) {
                float[] tagVec = tagVecs.get(i);
                if (tagVec != null) {
                    double sim = CosineSimilarity.compute(queryVec, tagVec);
                    scored.add(new ScoredTag(allTags.get(i), sim));
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

    public static String formatProfileSection(UserProfile profile, List<String> topTags) {
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

    public String formatProfileFromPreferences(String preferencesJson) {
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

    // =====================================================================
    // 情境闪现
    // =====================================================================

    public String buildFlashbackSection(UUID accountId, String context) {
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

    // =====================================================================
    // 文件快照
    // =====================================================================

    public String buildFileSnapshot(UUID accountId, UUID projectId) {
        if (projectId == null) return null;
        try {
            String rootPath = groupService.getProjectRootPath(accountId, projectId);
            if (rootPath == null) return null;
            Path root = Paths.get(rootPath);
            if (!Files.exists(root)) return null;
            String displayPath = groupService.getProjectDisplayPath(accountId, projectId);

            List<Path> recentFiles = new ArrayList<>();
            int fileCount = 0;
            int dirCount = 0;

            try (Stream<Path> stream = Files.walk(root, 3)) {
                var it = stream.iterator();
                while (it.hasNext()) {
                    Path p = it.next();
                    if (p.equals(root)) continue;
                    if (Files.isDirectory(p)) {
                        dirCount++;
                    } else if (Files.isRegularFile(p)) {
                        fileCount++;
                        recentFiles.add(p);
                    }
                }
            }

            recentFiles.sort((a, b) -> {
                try {
                    long ta = Files.getLastModifiedTime(a).toMillis();
                    long tb = Files.getLastModifiedTime(b).toMillis();
                    return Long.compare(tb, ta);
                } catch (IOException e) {
                    return 0;
                }
            });
            if (recentFiles.size() > 5) recentFiles = recentFiles.subList(0, 5);

            StringBuilder sb = new StringBuilder("## 工作目录状态\n");
            sb.append("路径：").append(displayPath).append("\n");
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

    // =====================================================================
    // 项目上下文
    // =====================================================================

    public String buildProjectHint(Conversation conversation, UUID accountId) {
        UUID projectId = conversation.getProjectId();
        if (projectId == null) {
            log.warn("对话缺少 projectId: conversationId={}", conversation.getConversationId());
            return null;
        }
        try {
            var project = groupService.findOwned(accountId, projectId);
            return ChatPrompts.projectContextHint(project.getName()).content();
        } catch (Exception e) {
            log.warn("解析项目上下文失败: accountId={}, projectId={}", accountId, projectId, e);
            return null;
        }
    }

    // =====================================================================
    // RAG 上下文
    // =====================================================================

    public String buildRagContext(String query, Conversation conversation, UUID accountId) {
        return ragContextBuilder.build(query, conversation, accountId);
    }

    // =====================================================================
    // 模型配置
    // =====================================================================

    public int resolveContextLength(UUID providerId, UUID accountId) {
        try {
            LlmProvider provider = providerId != null
                    ? modelRouter.getProvider(providerId)
                    : modelRouter.getDefaultProvider(accountId);
            Integer cl = provider.getContextLength();
            return cl != null && cl > 0 ? cl : 4096;
        } catch (Exception e) {
            return 4096;
        }
    }

    public double resolveBudgetRatio(UUID providerId, UUID accountId) {
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

    public double resolveBudgetRatio(UUID accountId) {
        return resolveBudgetRatio(null, accountId);
    }

    // =====================================================================
    // Token 估算
    // =====================================================================

    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        return (int) Math.ceil(text.length() / 2.0);
    }

    public static int estimateMessageTokens(Message msg) {
        int tokens = estimateTokens(msg.getContent());
        if (msg.getAttachments() != null) {
            tokens += estimateTokens(msg.getAttachments());
        }
        return tokens;
    }

    public int estimateMessagesTokens(List<Message> msgs, String systemPromptBase) {
        int total = estimateTokens(systemPromptBase);
        for (Message msg : msgs) {
            total += estimateMessageTokens(msg);
        }
        return total;
    }

    public static UUID findTargetMessageId(List<Message> messages, UUID excludeMessageId) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message message = messages.get(i);
            if (message.isUser() && !message.getMessageId().equals(excludeMessageId)) {
                return message.getMessageId();
            }
        }
        return null;
    }

    // =====================================================================
    // 工具解析
    // =====================================================================

    public static class ChatToolResult {
        public final List<ToolSpec> tools;
        public final List<Msg> contextMsgs;

        public ChatToolResult(List<ToolSpec> tools, List<Msg> contextMsgs) {
            this.tools = tools;
            this.contextMsgs = contextMsgs;
        }
    }

    public ChatToolResult resolveChatTools(Conversation conversation, String userContent,
                                           String toolConvContext, UUID accountId) {

        String userProfileSection = buildUserProfileSection(accountId, userContent);
        String flashbackSection = buildFlashbackSection(accountId, userContent);
        String fileSnapshot = buildFileSnapshot(accountId, conversation.getProjectId());
        String projectHint = buildProjectHint(conversation, accountId);

        List<Msg> contextMsgs = buildDynamicContextMsgs(userProfileSection, flashbackSection, fileSnapshot, projectHint);

        StringBuilder toolTextSb = new StringBuilder();

        List<ToolSpec> internalTools = getInternalTools();
        log.info("CHAT 工具解析: internalTools={} mcpServers={}",
                internalTools.stream().map(ToolSpec::name).toList(), conversation.getMcpServerIds());
        List<String> enabledServers = conversation.getMcpServerIds();
        if (enabledServers != null && !enabledServers.isEmpty()) {
            List<ToolSpec> mcpTools = resolveMcpTools(conversation);
            if (mcpTools != null) {
                List<ToolSpec> merged = new ArrayList<>(mcpTools);
                merged.addAll(internalTools);
                log.info("CHAT 工具解析: 合并模式 MCP={} 内部={}", mcpTools.size(), internalTools.size());
                return new ChatToolResult(merged, contextMsgs);
            }
            try {
                MatchedTools matched = toolAutoResolver.resolveForChat(userContent, toolConvContext, accountId);
                if (!matched.isEmpty()) {
                    var semanticTools = new ArrayList<>(toolEnricher.toSchemas(matched));
                    semanticTools.addAll(internalTools);
                    toolTextSb.append(toolEnricher.enrichPrompt("", matched.metas()));
                    log.info("CHAT 工具解析: 语义降级模式 tools={}",
                            semanticTools.stream().map(ToolSpec::name).toList());
                    if (!toolTextSb.isEmpty()) {
                        contextMsgs = appendToolTextToContextMsgs(contextMsgs, toolTextSb.toString());
                    }
                    return new ChatToolResult(semanticTools, contextMsgs);
                }
            } catch (Exception e) {
                log.warn("CHAT 工具语义匹配降级失败: {}", e.getMessage());
            }
        }
        if (internalTools.isEmpty()) {
            log.info("CHAT 工具解析: 无可用工具");
            return new ChatToolResult(null, contextMsgs);
        }
        log.info("CHAT 工具解析: 内部工具模式 tools={}", internalTools.stream().map(ToolSpec::name).toList());
        return new ChatToolResult(internalTools, contextMsgs);
    }

    public static List<Msg> buildDynamicContextMsgs(String userProfileSection, String flashbackSection,
                                                    String fileSnapshot, String projectHint) {
        StringBuilder ctxSb = new StringBuilder();
        if (userProfileSection != null) ctxSb.append("\n").append(userProfileSection);
        if (flashbackSection != null) ctxSb.append("\n\n").append(flashbackSection);
        if (fileSnapshot != null) ctxSb.append("\n\n").append(fileSnapshot);
        if (projectHint != null) ctxSb.append("\n\n").append(projectHint);

        if (ctxSb.isEmpty()) return List.of();
        return List.of(Msg.of(Role.USER, ctxSb.toString().strip()));
    }

    public static List<Msg> appendToolTextToContextMsgs(List<Msg> contextMsgs, String toolText) {
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

    public List<ToolSpec> getInternalTools() {
        Set<String> allowed = Set.of("bash", "file_read", "file_write", "file_list", "file_search");
        return toolRegistry.allSpecs().stream()
                .filter(s -> allowed.contains(s.name()))
                .toList();
    }

    public List<ToolSpec> resolveMcpTools(Conversation conversation) {
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
                    mcpConnectionManager.connectSync(sid);
                    if (mcpConnectionManager.isConnected(sid)) {
                        validServers.add(serverId);
                        anyConnected = true;
                    }
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
        return toolRegistry.allSpecs().stream()
                .map(s -> new ToolSpec(s.name(), s.description(), s.inputSchema()))
                .collect(java.util.stream.Collectors.toList());
    }


    // =====================================================================
    // 文件操作（沙盒附件复制）
    // =====================================================================

    public List<String> copyAttachmentsToSandbox(UUID accountId, Conversation conversation,
                                                 SendMessageRequest request) {
        UUID projectId = conversation.getProjectId();
        if (projectId == null) return List.of();

        List<Map<String, Object>> attachments = request.getAttachments();
        if (attachments == null || attachments.isEmpty()) return List.of();

        String sandboxPath = groupService.getProjectRootPath(accountId, projectId);
        if (sandboxPath == null) return List.of();

        Path uploadDir = Paths.get(sandboxPath);
        List<String> failedFiles = new ArrayList<>();

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

    public static String sanitizeFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) return null;
        String safe = Path.of(fileName).getFileName().toString();
        if (safe.isEmpty() || safe.equals(".") || safe.equals("..") || safe.startsWith(".")) return null;
        return safe;
    }

    // =====================================================================
    // 内部类型
    // =====================================================================

    public record ScoredTag(String tag, double score) {
    }

}
