package com.icusu.sivan.application.conversation.message;

import com.icusu.sivan.domain.conversation.Message;

import java.util.List;
import java.util.UUID;

/**
 * RAG 上下文富化器：将 RAG 检索结果注入目标用户消息末尾。
 */
public class RagContextEnricher implements MessageEnricher {

    private final String ragContext;
    private final UUID targetMessageId;

    /**
     * @param ragContext      RAG 检索到的上下文文本（为 null 时不执行富化）
     * @param targetMessageId 注入目标消息 ID（为 null 时不限制）
     */
    public RagContextEnricher(String ragContext, UUID targetMessageId) {
        this.ragContext = ragContext;
        this.targetMessageId = targetMessageId;
    }

    @Override
    public void enrich(EnrichedMessage enriched, List<Message> allMessages) {
        if (ragContext == null || ragContext.isBlank()) return;

        Message message = enriched.getOriginal();
        if (!message.isUser()) return;
        if (targetMessageId != null && !targetMessageId.equals(message.getMessageId())) return;

        enriched.appendContent("\n\n" + ragContext);
    }
}
