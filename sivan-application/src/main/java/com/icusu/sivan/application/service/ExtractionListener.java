package com.icusu.sivan.application.service;

import com.icusu.sivan.agent.compression.ExtractionService;
import com.icusu.sivan.core.message.Content;
import com.icusu.sivan.core.message.Msg;
import com.icusu.sivan.core.message.Role;
import com.icusu.sivan.core.model.Model;
import com.icusu.sivan.domain.conversation.IMessageRepository;
import com.icusu.sivan.domain.conversation.Message;
import com.icusu.sivan.domain.shared.event.MessageCompletedEvent;
import com.icusu.sivan.agent.model.DefaultModelRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 提取监听器 — 对话完成后触发结构化信息提取。
 * <p>
 * 在 {@link com.icusu.sivan.application.service.MemoryGenerationListener} 之后运行。
 * LLM 调用通过 subscribe 异步消费，无 .block()。
 */
@Component
public class ExtractionListener {

    private static final Logger log = LoggerFactory.getLogger(ExtractionListener.class);

    private final ExtractionService extractionService;
    private final IMessageRepository messageRepository;
    private final DefaultModelRouter defaultModelRouter;

    public ExtractionListener(ExtractionService extractionService,
                              IMessageRepository messageRepository,
                              DefaultModelRouter defaultModelRouter) {
        this.extractionService = extractionService;
        this.messageRepository = messageRepository;
        this.defaultModelRouter = defaultModelRouter;
    }

    @EventListener
    public void onMessageCompleted(MessageCompletedEvent event) {
        List<Message> messages;
        try {
            messages = messageRepository.findByConversationId(event.conversationId());
        } catch (Exception e) {
            log.warn("提取异常(不影响主流程): conversationId={}, {}", event.conversationId(), e.getMessage());
            return;
        }
        if (messages == null || messages.isEmpty()) return;

        // 取样 + 构建 prompt
        List<Message> sample = messages.size() > 50
                ? messages.subList(messages.size() - 50, messages.size()) : messages;
        String text = sample.stream()
                .map(m -> (m.getRole() != null ? m.getRole() : "unknown") + ": " + m.getContent())
                .collect(Collectors.joining("\n"));
        if (text.length() > 8000) text = text.substring(text.length() - 8000);

        String prompt = "从以下对话中提取：\n"
                + "1. 用户的关键决策（格式：- 决定: ...）\n"
                + "2. 事实性信息（格式：- 事实: ...）\n"
                + "3. 用户提到的技术栈/工具（格式：- 技术: ...）\n\n"
                + text;

        Model model = defaultModelRouter.getDefaultModel(event.accountId());
        if (model == null) {
            log.warn("[提取] 无可用模型, accountId={}", event.accountId());
            return;
        }

        model.chat(List.of(Msg.of(Role.USER, List.of(new Content.Text(prompt)))),
                        List.of(), Model.ModelParams.defaults())
                .subscribe(
                        response -> {
                            if (response != null && response.msg() != null) {
                                extractionService.parse(
                                        response.msg().text(),
                                        event.accountId(),
                                        event.conversationId().toString());
                            }
                        },
                        e -> log.warn("提取异常(不影响主流程): conversationId={}, {}",
                                event.conversationId(), e.getMessage())
                );
    }
}
