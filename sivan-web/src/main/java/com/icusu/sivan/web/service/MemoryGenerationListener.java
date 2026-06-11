package com.icusu.sivan.web.service;

import com.icusu.sivan.domain.conversation.IMessageRepository;
import com.icusu.sivan.domain.shared.event.MessageCompletedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 消息完成后异步触发记忆生成。
 * 通过领域事件解耦，不阻塞主流程。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MemoryGenerationListener {

    private final MemoryService memoryService;
    private final IMessageRepository messageRepository;

    @Async
    @EventListener
    public void onMessageCompleted(MessageCompletedEvent event) {
        try {
            messageRepository.findById(event.messageId()).ifPresent(assistantMsg -> memoryService.createFromConversation(event.accountId(), event.conversationId(), event.projectId(), assistantMsg));
            log.debug("记忆生成完成: conversationId={}", event.conversationId());
        } catch (Exception e) {
            log.warn("记忆生成异常: conversationId={}, {}", event.conversationId(), e.getMessage());
        }
    }
}
