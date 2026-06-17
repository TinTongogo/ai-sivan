package com.icusu.sivan.agent.compression;

import com.icusu.sivan.domain.memory.ExtractionResult;
import com.icusu.sivan.domain.memory.repository.StructuredMemoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * 信息提取服务 — 从 LLM 输出中解析结构化信息。
 * <p>
 * 纯文本处理，无阻塞调用。LLM 调用由调用方完成。
 */
@Component
public class ExtractionService {

    private static final Logger log = LoggerFactory.getLogger(ExtractionService.class);

    private final StructuredMemoryRepository memoryRepo;

    public ExtractionService(StructuredMemoryRepository memoryRepo) {
        this.memoryRepo = memoryRepo;
    }

    /**
     * 解析 LLM 输出文本，提取结构化信息并持久化。
     *
     * @param llmOutput      LLM 返回的原始文本
     * @param accountId      账户 ID
     * @param conversationId 对话 ID
     * @return 提取结果
     */
    public ExtractionResult parse(String llmOutput, UUID accountId, String conversationId) {
        ExtractionResult result = new ExtractionResult();
        if (llmOutput == null || llmOutput.isBlank()) return result;

        for (String line : llmOutput.split("\n")) {
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

        if (!result.isEmpty()) {
            try {
                memoryRepo.saveAll(result.toMemoryRecords(accountId, conversationId));
                log.info("[提取] 对话 {} 提取到 {} 条信息 (决策={}, 事实={}, 技术={})",
                        conversationId != null ? conversationId.substring(0, 8) : "?",
                        result.getDecisions().size() + result.getFacts().size() + result.getTechs().size(),
                        result.getDecisions().size(), result.getFacts().size(), result.getTechs().size());
            } catch (Exception e) {
                log.warn("[提取] 持久化失败: {}", e.getMessage());
            }
        }

        return result;
    }
}
