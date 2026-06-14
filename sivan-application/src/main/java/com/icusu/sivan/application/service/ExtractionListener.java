package com.icusu.sivan.application.service;

import com.icusu.sivan.agent.compression.ExtractionService;
import com.icusu.sivan.domain.conversation.IMessageRepository;
import com.icusu.sivan.domain.shared.event.MessageCompletedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 提取监听器 — 对话完成后触发结构化信息提取。
 * <p>
 * 在 {@link MemoryGenerationListener} 之后运行，提取决策/事实/技术等结构化信息。
 */
@Component
public class ExtractionListener {

    private static final Logger log = LoggerFactory.getLogger(ExtractionListener.class);

    private final ExtractionService extractionService;
    private final IMessageRepository messageRepository;

    public ExtractionListener(ExtractionService extractionService, IMessageRepository messageRepository) {
        this.extractionService = extractionService;
        this.messageRepository = messageRepository;
    }

    @EventListener
    public void onMessageCompleted(MessageCompletedEvent event) {
        try {
            var messages = messageRepository.findByConversationId(event.conversationId());
            if (messages == null || messages.isEmpty()) return;

            extractionService.extract(messages, event.accountId(), event.conversationId().toString());
            log.debug("提取完成: conversationId={}", event.conversationId());
        } catch (Exception e) {
            log.warn("提取异常(不影响主流程): conversationId={}, {}", event.conversationId(), e.getMessage());
        }
    }
}
