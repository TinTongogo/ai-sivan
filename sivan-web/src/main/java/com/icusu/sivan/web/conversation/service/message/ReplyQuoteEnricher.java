package com.icusu.sivan.web.conversation.service.message;

import com.icusu.sivan.domain.conversation.Message;
import com.icusu.sivan.domain.conversation.IMessageRepository;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Objects;

/**
 * 引用回复富化器：将被引消息内容注入到当前用户消息中。
 */
@Slf4j
public class ReplyQuoteEnricher implements MessageEnricher {

    private final IMessageRepository messageRepository;

    public ReplyQuoteEnricher(IMessageRepository messageRepository) {
        this.messageRepository = messageRepository;
    }

    @Override
    public void enrich(EnrichedMessage enriched, List<Message> allMessages) {
        Message message = enriched.getOriginal();
        if (!message.isUser() || message.getReplyToId() == null) return;

        // 优先从 allMessages 中查找，避免查询数据库
        String quoted = allMessages.stream()
                .filter(m -> m.getMessageId().equals(message.getReplyToId()))
                .map(Message::getContent)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);

        if (quoted != null && !quoted.isBlank()) {
            enriched.prependContent("引用「" + quoted.strip() + "」\n");
            return;
        }

        // 未命中缓存，回退数据库查询
        messageRepository.findById(message.getReplyToId()).ifPresent(replied -> {
            String c = replied.getContent();
            if (c != null && !c.isBlank()) {
                enriched.prependContent("引用「" + c.strip() + "」\n");
            }
        });
    }
}
