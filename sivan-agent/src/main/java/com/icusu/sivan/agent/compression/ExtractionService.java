package com.icusu.sivan.agent.compression;

import com.icusu.sivan.agent.model.DefaultModelRouter;
import com.icusu.sivan.core.message.Content;
import com.icusu.sivan.core.message.Msg;
import com.icusu.sivan.core.message.Role;
import com.icusu.sivan.core.model.Model;
import com.icusu.sivan.domain.compression.ExtractionResult;
import com.icusu.sivan.domain.compression.StructuredMemoryRepository;
import com.icusu.sivan.domain.conversation.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 信息提取服务 — 从对话中提取结构化信息。
 * <p>
 * 设计文档 4.4 节。提取结果存入 StructuredMemory，下次压缩时可直接引用。
 */
@Component
public class ExtractionService {

    private static final Logger log = LoggerFactory.getLogger(ExtractionService.class);

    private final DefaultModelRouter modelRouter;
    private final StructuredMemoryRepository memoryRepo;

    public ExtractionService(DefaultModelRouter modelRouter, StructuredMemoryRepository memoryRepo) {
        this.modelRouter = modelRouter;
        this.memoryRepo = memoryRepo;
    }

    /**
     * 从消息列表中提取关键信息。
     *
     * @param messages  消息列表
     * @param accountId 账户 ID
     * @param conversationId 对话 ID
     * @return 提取结果（决策/事实/技术）
     */
    public ExtractionResult extract(List<Message> messages, UUID accountId, String conversationId) {
        if (messages == null || messages.isEmpty()) {
            return new ExtractionResult();
        }

        // 取样：最后 50 条或全量
        List<Message> sample = messages.size() > 50
                ? messages.subList(messages.size() - 50, messages.size())
                : messages;

        String text = sample.stream()
                .map(m -> (m.getRole() != null ? m.getRole() : "unknown") + ": " + m.getContent())
                .collect(Collectors.joining("\n"));

        if (text.length() > 8000) {
            text = text.substring(text.length() - 8000);
        }

        ExtractionResult result = llmExtract(text, accountId);

        // 持久化提取结果
        if (!result.isEmpty()) {
            memoryRepo.saveAll(result.toMemoryRecords(accountId, conversationId));
            log.info("[提取] 对话 {} 提取到 {} 条信息 (决策={}, 事实={}, 技术={})",
                    conversationId != null ? conversationId.substring(0, 8) : "?",
                    result.getDecisions().size() + result.getFacts().size() + result.getTechs().size(),
                    result.getDecisions().size(), result.getFacts().size(), result.getTechs().size());
        }

        return result;
    }

    private ExtractionResult llmExtract(String text, UUID accountId) {
        ExtractionResult result = new ExtractionResult();
        try {
            Model model = modelRouter.getDefaultModel(accountId);
            String prompt = "从以下对话中提取：\n"
                    + "1. 用户的关键决策（格式：- 决定: ...）\n"
                    + "2. 事实性信息（格式：- 事实: ...）\n"
                    + "3. 用户提到的技术栈/工具（格式：- 技术: ...）\n\n"
                    + text;

            Model.ModelResponse response = model.chat(
                    List.of(Msg.of(Role.USER, List.of(new Content.Text(prompt)))),
                    List.of(),
                    Model.ModelParams.defaults()
            ).block();

            if (response != null && response.msg() != null) {
                parseExtraction(response.msg().text(), result);
            }
        } catch (Exception e) {
            log.warn("[提取] LLM 提取失败: {}", e.getMessage());
        }
        return result;
    }

    private void parseExtraction(String text, ExtractionResult result) {
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("- 决定:") || trimmed.startsWith("- 决定：")) {
                String v = trimmed.substring(4).trim();
                if (!v.isEmpty()) result.getDecisions().add(v);
            } else if (trimmed.startsWith("- 事实:") || trimmed.startsWith("- 事实：")) {
                String v = trimmed.substring(4).trim();
                if (!v.isEmpty()) result.getFacts().add(v);
            } else if (trimmed.startsWith("- 技术:") || trimmed.startsWith("- 技术：")) {
                String v = trimmed.substring(4).trim();
                if (!v.isEmpty()) result.getTechs().add(v);
            }
        }
    }
}
