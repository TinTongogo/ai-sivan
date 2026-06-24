package com.icusu.sivan.application.conversation;

import com.icusu.sivan.application.conversation.dto.SendMessageRequest;
import com.icusu.sivan.application.conversation.message.CoreMessageBuilder;
import com.icusu.sivan.application.conversation.tree.ContextResult;
import com.icusu.sivan.core.message.Msg;
import com.icusu.sivan.core.tool.ToolSpec;
import com.icusu.sivan.domain.conversation.CompressResult;
import com.icusu.sivan.domain.conversation.Conversation;
import com.icusu.sivan.domain.conversation.Message;
import com.icusu.sivan.domain.file.FileStoragePort;
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
    public int resolveContextLength(UUID providerId, UUID accountId) {
        return messageEnrichmentService.resolveContextLength(providerId, accountId);
    }

    public double resolveBudgetRatio(UUID providerId, UUID accountId) {
        return messageEnrichmentService.resolveBudgetRatio(providerId, accountId);
    }

    public static int estimateTokens(String text) {
        return MessageEnrichmentService.estimateTokens(text);
    }

    public List<Msg> buildLlmMessages(UUID conversationId, UUID accountId, List<String> images, List<String> audios,
                                      int contextLength, UUID excludeMessageId, String ragContext,
                                      String compressedContext, Set<UUID> protectMsgIds, UUID providerId,
                                      List<ToolSpec> internalTools, List<ToolSpec> externalTools,
                                      List<Msg> contextMsgs) {
        var enriched = messageEnrichmentService.enrichMessages(conversationId, images, audios,
                contextLength, excludeMessageId, ragContext, protectMsgIds, accountId, providerId);
        var coreBuilder = new CoreMessageBuilder(fileStorageService);
        return coreBuilder.build(com.icusu.sivan.agent.prompt.ChatPrompts.CHAT_SYSTEM.content(),
                internalTools, externalTools, contextMsgs, compressedContext,
                enriched, excludeMessageId, accountId);
    }

    // ===== 委托给 ContextAssemblyService =====
    public ContextResult buildEpochContextResult(UUID conversationId, CompressResult compressResult,
                                                  int historyBudget, List<Message> allMessages) {
        return contextAssemblyService.buildEpochContextResult(conversationId, compressResult, historyBudget, allMessages);
    }

    public String buildProjectHint(Conversation conversation, UUID accountId) {
        return contextAssemblyService.buildProjectHint(conversation, accountId);
    }

    // ===== 委托给 ToolResolutionService =====

    public List<ToolSpec> getInternalTools() {
        return toolResolutionService.getInternalTools();
    }

    public ToolResolutionService.ChatToolResult resolveChatTools(Conversation conversation, String userContent,
                                                                  String toolConvContext, UUID accountId) {
        return toolResolutionService.resolveChatTools(conversation, userContent, toolConvContext, accountId);
    }

    public List<String> copyAttachmentsToSandbox(UUID accountId, Conversation conversation,
                                                  SendMessageRequest request) {
        return toolResolutionService.copyAttachmentsToSandbox(accountId, conversation, request);
    }
}
