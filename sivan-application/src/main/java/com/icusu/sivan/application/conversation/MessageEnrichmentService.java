package com.icusu.sivan.application.conversation;

import com.icusu.sivan.agent.model.ModelCapabilityRegistry;
import com.icusu.sivan.agent.model.ModelRouter;
import com.icusu.sivan.agent.prompt.ChatPrompts;
import com.icusu.sivan.application.conversation.message.*;
import com.icusu.sivan.domain.conversation.Message;
import com.icusu.sivan.domain.model.LlmProvider;
import com.icusu.sivan.domain.model.ModelCapability;
import com.icusu.sivan.infra.file.DocumentTextExtractor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 消息富化服务 — 消息加载、截断、富化、Token 估算。
 * <p>
 * 从 {@link PromptContextService} 拆出。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MessageEnrichmentService {

    private final ModelRouter modelRouter;
    private final ModelCapabilityRegistry modelCapabilityRegistry;
    private final DocumentTextExtractor documentTextExtractor;
    private final com.icusu.sivan.domain.file.FileStoragePort fileStorageService;
    private final com.icusu.sivan.domain.conversation.IMessageRepository messageRepository;

    public static final int MAX_MESSAGES_LOAD = 500;

    public List<EnrichedMessage> enrichMessages(
            UUID conversationId, List<String> images, List<String> audios,
            int contextLength, UUID excludeMessageId, String ragContext,
            Set<UUID> protectMsgIds, UUID accountId, UUID providerId) {
        List<Message> messages = new ArrayList<>(
                messageRepository.findLatestByConversationId(conversationId, MAX_MESSAGES_LOAD));

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

    // ====== Token 估算 ======

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
}
