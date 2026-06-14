package com.icusu.sivan.application.conversation;

import com.icusu.sivan.core.message.Msg;
import com.icusu.sivan.core.message.Role;
import com.icusu.sivan.core.tool.ToolSpec;
import com.icusu.sivan.domain.account.IAccountRepository;
import com.icusu.sivan.domain.account.IUserProfileRepository;
import com.icusu.sivan.domain.conversation.*;
import com.icusu.sivan.agent.mcp.McpConnectionManager;
import com.icusu.sivan.agent.model.ModelCapabilityRegistry;
import com.icusu.sivan.agent.model.ModelRouter;
import com.icusu.sivan.agent.tool.ToolEnricher;
import com.icusu.sivan.agent.tool.ToolRegistryImpl;
import com.icusu.sivan.agent.tool.ToolResolver;
import com.icusu.sivan.domain.file.FileStoragePort;
import com.icusu.sivan.domain.shared.port.IEmbeddingService;
import com.icusu.sivan.infra.file.DocumentTextExtractor;
import com.icusu.sivan.infra.memory.flashback.FlashbackScanner;
import com.icusu.sivan.application.conversation.dto.SendMessageRequest;
import com.icusu.sivan.application.conversation.tree.ContextBuilder;
import com.icusu.sivan.application.conversation.tree.ContextCache;
import com.icusu.sivan.application.conversation.tree.ContextResult;
import com.icusu.sivan.application.knowledge.RagContextBuilder;
import com.icusu.sivan.application.conversation.message.CoreMessageBuilder;
import com.icusu.sivan.application.service.GroupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Prompt 上下文服务 — 委托层，实际逻辑在 {@link MessageEnrichmentService}、
 * {@link ContextAssemblyService}、{@link ToolResolutionService} 中。
 * <p>
 * 保持此类对外接口不変，避免影响已有调用方。新代码请直接使用三个子服务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PromptContextService {

    private final FileStoragePort fileStorageService;
    private final MessageEnrichmentService messageEnrichmentService;
    private final ContextAssemblyService contextAssemblyService;
    private final ToolResolutionService toolResolutionService;

    // ===== 委托给 MessageEnrichmentService =====

    public List<Message> truncateWithProtection(List<Message> messages, String systemPrompt,
                                                int maxPromptTokens, Set<UUID> protectMsgIds) {
        return messageEnrichmentService.truncateWithProtection(messages, systemPrompt, maxPromptTokens, protectMsgIds);
    }

    public static void insertContextMessages(List<Msg> msgs, ContextResult epochResult,
                                             String fallbackContext) {
        MessageEnrichmentService.insertContextMessages(msgs, epochResult, fallbackContext);
    }

    public List<com.icusu.sivan.application.conversation.message.EnrichedMessage> enrichMessages(
            UUID conversationId, List<String> images, List<String> audios,
            int contextLength, UUID excludeMessageId, String ragContext,
            Set<UUID> protectMsgIds, UUID accountId, UUID providerId) {
        return messageEnrichmentService.enrichMessages(conversationId, images, audios,
                contextLength, excludeMessageId, ragContext, protectMsgIds, accountId, providerId);
    }

    public int resolveContextLength(UUID providerId, UUID accountId) {
        return messageEnrichmentService.resolveContextLength(providerId, accountId);
    }

    public double resolveBudgetRatio(UUID providerId, UUID accountId) {
        return messageEnrichmentService.resolveBudgetRatio(providerId, accountId);
    }

    public double resolveBudgetRatio(UUID accountId) {
        return messageEnrichmentService.resolveBudgetRatio(accountId);
    }

    public static int estimateTokens(String text) {
        return MessageEnrichmentService.estimateTokens(text);
    }

    public static int estimateMessageTokens(Message msg) {
        return MessageEnrichmentService.estimateMessageTokens(msg);
    }

    public List<Msg> buildLlmMessages(UUID conversationId, UUID accountId, List<String> images, List<String> audios,
                                      int contextLength, UUID excludeMessageId, String ragContext,
                                      String compressedContext, Set<UUID> protectMsgIds, UUID providerId) {
        var enriched = messageEnrichmentService.enrichMessages(conversationId, images, audios,
                contextLength, excludeMessageId, ragContext, protectMsgIds, accountId, providerId);
        var coreBuilder = new CoreMessageBuilder(fileStorageService);
        var msgs = coreBuilder.build(com.icusu.sivan.agent.prompt.ChatPrompts.CHAT_SYSTEM.content(),
                enriched, excludeMessageId, accountId);
        if (compressedContext != null && !compressedContext.isBlank()) {
            msgs.add(1, Msg.of(Role.USER,
                    com.icusu.sivan.agent.prompt.ChatPrompts.contextInjection(compressedContext).content()));
        }
        return msgs;
    }

    // ===== 委托给 ContextAssemblyService =====

    public String buildEpochContext(UUID conversationId, CompressResult compressResult,
                                    int historyBudget, List<Message> allMessages) {
        return contextAssemblyService.buildEpochContext(conversationId, compressResult, historyBudget, allMessages);
    }

    public ContextResult buildEpochContextResult(UUID conversationId, CompressResult compressResult,
                                                  int historyBudget, List<Message> allMessages) {
        return contextAssemblyService.buildEpochContextResult(conversationId, compressResult, historyBudget, allMessages);
    }

    public String buildUserProfileSection(UUID accountId) {
        return contextAssemblyService.buildUserProfileSection(accountId);
    }

    public String buildFlashbackSection(UUID accountId, String context) {
        return contextAssemblyService.buildFlashbackSection(accountId, context);
    }

    public String buildFileSnapshot(UUID accountId, UUID projectId) {
        return contextAssemblyService.buildFileSnapshot(accountId, projectId);
    }

    public String buildProjectHint(Conversation conversation, UUID accountId) {
        return contextAssemblyService.buildProjectHint(conversation, accountId);
    }

    public String buildRagContext(String query, Conversation conversation, UUID accountId) {
        return contextAssemblyService.buildRagContext(query, conversation, accountId);
    }

    // ===== 委托给 ToolResolutionService =====

    public ToolResolutionService.ChatToolResult resolveChatTools(Conversation conversation, String userContent,
                                                                  String toolConvContext, UUID accountId) {
        return toolResolutionService.resolveChatTools(conversation, userContent, toolConvContext, accountId);
    }

    public List<String> copyAttachmentsToSandbox(UUID accountId, Conversation conversation,
                                                  SendMessageRequest request) {
        return toolResolutionService.copyAttachmentsToSandbox(accountId, conversation, request);
    }
}
