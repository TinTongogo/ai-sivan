package com.icusu.sivan.web.memory.service;

import com.icusu.sivan.agent.model.ModelRouter;
import com.icusu.sivan.common.enums.MemoryLevel;
import com.icusu.sivan.core.message.Msg;
import com.icusu.sivan.core.message.Role;
import com.icusu.sivan.core.model.Model;
import com.icusu.sivan.domain.memory.IMemoryRepository;
import com.icusu.sivan.domain.memory.MemoryEntry;
import com.icusu.sivan.domain.shared.util.CosineSimilarity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * 记忆合并去重器。
 * <p>
 * 对同一 scopeId（对话）的 SESSION 级记忆，按向量余弦相似度分组。
 * 相似度超阈值的条目合并：content 拼接、summary LLM 重新摘要（≤80 字）、
 * retention 取 max、accessCount 累加，旧条目标记 archived=true。
 * <p>
 * LLM 摘要失败时退回到简单拼接，不阻塞主流程。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MemoryConsolidator {

    private final IMemoryRepository memoryRepository;
    private final ModelRouter modelRouter;

    /** 向量相似度阈值：高于此值视为相似触发合并。 */
    private static final double SIMILARITY_THRESHOLD = 0.85;

    /**
     * 对指定对话的 SESSION 记忆执行合并去重。
     * 可在异步上下文中安全调用（内部全部 try-catch 降级）。
     */
    public void consolidate(UUID accountId, UUID conversationId) {
        try {
            List<MemoryEntry> sessionMemories = memoryRepository.findByLevelAndScope(
                    accountId, MemoryLevel.SESSION, conversationId.toString());
            if (sessionMemories == null || sessionMemories.size() < 2) return;

            // 过滤出有向量且未归档的
            List<MemoryEntry> active = sessionMemories.stream()
                    .filter(m -> m.getVector() != null && !Boolean.TRUE.equals(m.getArchived()))
                    .toList();
            if (active.size() < 2) return;

            boolean[] merged = new boolean[active.size()];
            for (int i = 0; i < active.size(); i++) {
                if (merged[i]) continue;
                MemoryEntry base = active.get(i);

                for (int j = i + 1; j < active.size(); j++) {
                    if (merged[j]) continue;
                    MemoryEntry other = active.get(j);

                    double similarity = CosineSimilarity.compute(base.getVector(), other.getVector());
                    if (similarity >= SIMILARITY_THRESHOLD) {
                        // 合并：保留 retention 更高的、accessCount 累加、content 拼接
                        float mergedRetention = Math.max(
                                base.getRetention() != null ? base.getRetention() : 0,
                                other.getRetention() != null ? other.getRetention() : 0);
                        int mergedAccessCount = (base.getAccessCount() != null ? base.getAccessCount() : 0)
                                + (other.getAccessCount() != null ? other.getAccessCount() : 0);
                        String mergedContent = base.getContent() != null ? base.getContent() : "";
                        if (other.getContent() != null && !other.getContent().isBlank()
                                && !mergedContent.contains(other.getContent())) {
                            mergedContent += "\n\n" + other.getContent();
                        }

                        // LLM 重新生成合并后的摘要
                        String mergedSummary = generateMergedSummary(
                                accountId, mergedContent, base.getSummary(), other.getSummary());

                        base.setContent(mergedContent);
                        base.setSummary(mergedSummary);
                        base.setRetention(mergedRetention);
                        base.setAccessCount(mergedAccessCount);

                        // 旧条目归档
                        other.setArchived(true);
                        memoryRepository.save(other);
                        merged[j] = true;
                    }
                }
                memoryRepository.save(base);
            }

            int mergedCount = (int) java.util.stream.IntStream.range(0, merged.length)
                    .filter(i -> merged[i])
                    .count();
            if (mergedCount > 0) {
                log.debug("记忆合并完成: conversationId={}, 合并了 {} 条", conversationId, mergedCount);
            }
        } catch (Exception e) {
            log.warn("记忆合并去重失败(不影响主流程): {}", e.getMessage());
        }
    }

    /**
     * 用 LLM 生成合并后内容的摘要。失败时退回到两条原摘要拼接。
     */
    private String generateMergedSummary(UUID accountId, String mergedContent,
                                          String summaryA, String summaryB) {
        if (mergedContent == null || mergedContent.isBlank()) {
            return summaryA != null ? summaryA : "";
        }

        try {
            String prompt = "以下两段对话内容讨论的是同一话题，请用一句话概括它们的核心信息（不超过80字）：\n\n"
                    + mergedContent;
            List<Msg> msgs = List.of(Msg.of(Role.USER, prompt));
            String result = modelRouter.getDefaultModel(accountId)
                    .chat(msgs, Model.ModelParams.defaults())
                    .map(response -> {
                        String text = response.msg().text();
                        if (text != null) {
                            text = text.strip();
                            if (text.length() > 80) text = text.substring(0, 77) + "...";
                        }
                        return text != null ? text : "";
                    })
                    .block(Duration.ofSeconds(5));

            if (result != null && !result.isBlank()) return result;
        } catch (Exception e) {
            log.debug("合并摘要 LLM 调用失败，使用拼接摘要: {}", e.getMessage());
        }

        // 降级：拼接两条摘要
        String merged = summaryA != null ? summaryA : "";
        if (summaryB != null && !summaryB.isBlank()) {
            merged = merged.isBlank() ? summaryB : merged + "；" + summaryB;
        }
        return merged.length() > 80 ? merged.substring(0, 77) + "..." : merged;
    }
}
