package com.icusu.sivan.web.conversation.service.compress;

import com.icusu.sivan.domain.conversation.CompressResult;
import com.icusu.sivan.domain.conversation.IMessageRepository;
import com.icusu.sivan.domain.conversation.Message;
import com.icusu.sivan.web.conversation.service.PromptContextService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 结构感知提取压缩器 (Structure-Aware Extractive Compressor)。
 * <p>
 * 根据 LLM 上下文窗口大小自适应选择压缩策略，零 LLM 调用。
 * 仅通过评分/选择/句级截断保留原始对话中的关键信息，不生成新文本。
 * <p>
 * 策略选择（基于 budget/totalTokens 比值）：
 * <ul>
 *   <li>≥1.0 → PASSTHROUGH：预算充足，全部原文保留</li>
 *   <li>≥0.6 → LIGHT：去重 + 过滤填充语</li>
 *   <li>≥0.3 → MODERATE：消息级评分 + 分档选择（完整保留选中消息）</li>
 *   <li>&lt;0.3 → AGGRESSIVE：消息级评分 + 句级提取（对放不下的消息提取关键句）</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConversationCompressor {

    private final IMessageRepository messageRepository;

    // ====== 公开入口 ======

    /**
     * 压缩对话消息（从 repository 加载）。
     *
     * @param conversationId 对话 ID
     * @param historyBudget  历史预算（token），由 LLM contextLength 折算
     * @param currentQuery   用户当前输入，用于关键词相关性评分
     * @return CompressResult（摘要文本 + HOT 批次 + 重要消息 ID）
     */
    public Mono<CompressResult> compress(UUID conversationId, int historyBudget, String currentQuery) {
        return Mono.defer(() -> {
            List<Message> allMessages = messageRepository.findByConversationId(conversationId);
            return compress(allMessages, historyBudget, currentQuery);
        });
    }

    /**
     * 压缩对话消息（使用预加载消息列表，避免重复查询）。
     *
     * @param allMessages  预加载的对话消息列表
     * @param historyBudget 历史预算（token）
     * @param currentQuery  用户当前输入
     * @return CompressResult
     */
    public Mono<CompressResult> compress(List<Message> allMessages, int historyBudget, String currentQuery) {
        List<Message> messages = allMessages.stream()
                .filter(m -> m.isUser() || m.isAssistant())
                .collect(Collectors.toList());
        if (messages.isEmpty()) {
            return Mono.just(new CompressResult("", List.of(), List.of(), null, true));
        }

        int totalTokens = estimateMessagesTokens(messages);
        Level level = selectLevel(totalTokens, historyBudget);
        log.debug("Compress: msgCount={}, totalTokens={}, budget={}, level={}",
                messages.size(), totalTokens, historyBudget, level);

        UUID taskGoalMsgId = findFirstUserMsgId(messages);
        List<UUID> allMsgIds = extractMsgIds(messages);

        return switch (level) {
            case PASSTHROUGH -> handlePassThrough(messages, taskGoalMsgId, allMsgIds);
            case LIGHT -> handleLight(messages, taskGoalMsgId, historyBudget, currentQuery);
            case MODERATE -> handleModerate(messages, taskGoalMsgId, historyBudget, currentQuery);
            case AGGRESSIVE -> handleAggressive(messages, taskGoalMsgId, historyBudget, currentQuery);
        };
    }

    // ====== 策略等级 ======

    public enum Level {
        PASSTHROUGH, LIGHT, MODERATE, AGGRESSIVE
    }

    Level selectLevel(int totalTokens, int budget) {
        double ratio = (double) budget / Math.max(totalTokens, 1);
        if (ratio >= 1.0) return Level.PASSTHROUGH;
        if (ratio >= 0.6) return Level.LIGHT;
        if (ratio >= 0.3) return Level.MODERATE;
        return Level.AGGRESSIVE;
    }

    // ====== PASSTHROUGH ======

    private Mono<CompressResult> handlePassThrough(List<Message> messages, UUID taskGoalMsgId,
                                                    List<UUID> allMsgIds) {
        String summary = formatMessages(messages);
        return Mono.just(new CompressResult(summary, messages, allMsgIds, taskGoalMsgId, true));
    }

    // ====== LIGHT ======

    private Mono<CompressResult> handleLight(List<Message> messages, UUID taskGoalMsgId,
                                              int budget, String query) {
        // 1. 去重（内容完全相同的相邻消息）
        List<Message> cleaned = deduplicate(messages);
        int cleanedTokens = estimateMessagesTokens(cleaned);
        if (cleanedTokens <= budget) {
            List<UUID> ids = extractMsgIds(cleaned);
            return Mono.just(new CompressResult(formatMessages(cleaned), cleaned, ids, taskGoalMsgId, true));
        }

        // 2. 移除纯填充语
        cleaned = removeFiller(cleaned);
        cleanedTokens = estimateMessagesTokens(cleaned);
        if (cleanedTokens <= budget) {
            List<UUID> ids = extractMsgIds(cleaned);
            return Mono.just(new CompressResult(formatMessages(cleaned), cleaned, ids, taskGoalMsgId, true));
        }

        // 3. 仍超预算 → 降级到 MODERATE
        return handleModerate(cleaned, taskGoalMsgId, budget, query);
    }

    // ====== MODERATE ======

    private Mono<CompressResult> handleModerate(List<Message> messages, UUID taskGoalMsgId,
                                                 int budget, String query) {
        return scoreAndSelect(messages, taskGoalMsgId, budget, query, false);
    }

    // ====== AGGRESSIVE ======

    private Mono<CompressResult> handleAggressive(List<Message> messages, UUID taskGoalMsgId,
                                                   int budget, String query) {
        return scoreAndSelect(messages, taskGoalMsgId, budget, query, true);
    }

    // ====== 评分选择核心 ======

    private Mono<CompressResult> scoreAndSelect(List<Message> messages, UUID taskGoalMsgId,
                                                 int budget, String query, boolean allowExtract) {
        int total = messages.size();

        // 1. 评分 + 识别锚点
        Set<Integer> anchors = findAnchors(messages, taskGoalMsgId);
        List<ScoredMsg> candidates = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            if (!anchors.contains(i)) {
                double score = scoreMessage(messages.get(i), i, total, query);
                candidates.add(new ScoredMsg(messages.get(i), i, score));
            }
        }
        candidates.sort((a, b) -> Double.compare(b.score, a.score));

        // 2. 锚点预留
        int anchorTokens = 0;
        for (int idx : anchors) {
            anchorTokens += estimateMessageTokens(messages.get(idx));
        }
        int reserved = Math.min(anchorTokens, budget);
        int remaining = budget - reserved;

        // Tier 1: 高分完整保留 (50% of remaining)
        int tier1Budget = remaining / 2;
        // Tier 2: 填充 (50%, AGGRESSIVE 可句级提取)
        int tier2Budget = remaining - tier1Budget;

        // 3. Tier 1 选择
        List<Message> tier1Selected = new ArrayList<>();
        int tier1Used = 0;
        for (ScoredMsg sm : candidates) {
            int t = estimateMessageTokens(sm.msg);
            if (tier1Used + t <= tier1Budget) {
                tier1Selected.add(sm.msg);
                tier1Used += t;
            }
        }

        // 4. 重建保留消息列表（按原始顺序）
        Set<Message> selectedSet = new HashSet<>(tier1Selected);
        List<Message> timeline = new ArrayList<>();
        for (int idx : anchors) timeline.add(messages.get(idx));
        for (Message m : tier1Selected) timeline.add(m);
        timeline.sort(Comparator.comparingInt(m -> messages.indexOf(m)));

        // 5. Tier 2 处理
        List<String> tier2Parts = new ArrayList<>();
        int tier2Used = 0;
        if (allowExtract) {
            for (ScoredMsg sm : candidates) {
                if (selectedSet.contains(sm.msg)) continue;
                int room = tier2Budget - tier2Used;
                if (room <= 0) break;
                String extracted = extractSentences(sm.msg, room * 2); // chars ≈ tokens × 2
                if (!extracted.isBlank()) {
                    int cost = estimateTokens(extracted);
                    tier2Used += cost;
                    tier2Parts.add(extracted);
                }
            }
        } else {
            for (ScoredMsg sm : candidates) {
                if (selectedSet.contains(sm.msg)) continue;
                int t = estimateMessageTokens(sm.msg);
                if (tier2Used + t <= tier2Budget) {
                    tier2Parts.add(formatMessage(sm.msg));
                    tier2Used += t;
                } else {
                    break;
                }
            }
        }

        // 6. 组装 summary
        StringBuilder sb = new StringBuilder();
        for (Message m : timeline) sb.append(formatMessage(m));
        if (!tier2Parts.isEmpty()) {
            sb.append("...(以下为压缩)").append("\n");
            for (String p : tier2Parts) sb.append(p);
        }

        // 7. HOT batch（最近消息全文）
        int hotBudget = Math.max(budget / 2, 512);
        List<Message> hotBatch = buildHotBatch(timeline, hotBudget);

        // 8. Important IDs
        Set<UUID> importantIds = new LinkedHashSet<>();
        if (taskGoalMsgId != null) importantIds.add(taskGoalMsgId);
        for (Message m : timeline) {
            if (m.isUser() && m.getMessageId() != null) importantIds.add(m.getMessageId());
        }
        for (Message m : hotBatch) {
            if (m.isUser() && m.getMessageId() != null) importantIds.add(m.getMessageId());
        }

        return Mono.just(new CompressResult(
                sb.toString().trim(),
                hotBatch,
                new ArrayList<>(importantIds),
                taskGoalMsgId,
                false));
    }

    // ====== HOT batch 构建 ======

    /**
     * 从 timeline 中提取最近的消息作为 HOT batch。
     * 优先选取用户消息，填满 hotBudget 预算。
     */
    private List<Message> buildHotBatch(List<Message> timeline, int hotBudget) {
        List<Message> reversed = new ArrayList<>(timeline);
        Collections.reverse(reversed);

        List<Message> batch = new ArrayList<>();
        int used = 0;
        for (Message m : reversed) {
            int t = estimateMessageTokens(m);
            if (used + t > hotBudget) break;
            batch.add(m);
            used += t;
        }
        Collections.reverse(batch);
        return batch;
    }

    // ====== 消息评分 ======

    double scoreMessage(Message msg, int index, int total, String query) {
        double score = 0;

        // 角色权重：用户消息承载任务信息
        score += msg.isUser() ? 10 : 2;

        // 位置权重
        if (index == 0) score += 15;                // 首条（任务目标）
        if (index == total - 1) score += 10;        // 末条（当前上下文）
        // 最后 25% 有新鲜度加成
        if (total > 1 && index >= total * 0.75) {
            score += (index - total * 0.75) / (total * 0.25) * 6;
        }

        // 内容信号
        String c = msg.getContent();
        if (c != null && !c.isBlank()) {
            if (c.contains("```")) score += 8;          // 代码块
            if (c.contains("http")) score += 3;          // 链接
            if (c.contains("?")) score += 2;             // 问句
            if (c.length() > 200) score += 3;            // 长消息信息密度高
            if (isFiller(c)) score -= 5;                 // 纯填充语

            // 查询关键词匹配
            if (query != null && !query.isBlank()) {
                String ql = query.toLowerCase();
                String cl = c.toLowerCase();
                int matches = 0;
                for (String w : ql.split("[\\s,，。；;：:、！!？?]+")) {
                    if (w.length() > 1 && cl.contains(w)) matches++;
                }
                score += matches * 1.5;
            }
        }

        return score;
    }

    // ====== 锚点识别 ======

    /**
     * 识别必须保留的结构性锚点消息：
     * - 第一条消息（对话起始）
     * - 第一条用户消息（任务目标）
     * - 最后一条用户消息（当前请求）
     * - 含代码块的消息（高信息密度）
     */
    Set<Integer> findAnchors(List<Message> messages, UUID taskGoalMsgId) {
        Set<Integer> anchors = new LinkedHashSet<>();
        int total = messages.size();
        if (total == 0) return anchors;

        // 首条消息
        anchors.add(0);

        // 任务目标（第一条用户消息）
        if (taskGoalMsgId != null) {
            for (int i = 0; i < total; i++) {
                if (taskGoalMsgId.equals(messages.get(i).getMessageId())) {
                    anchors.add(i);
                    break;
                }
            }
        }

        // 最后一条用户消息
        for (int i = total - 1; i >= 0; i--) {
            if (messages.get(i).isUser()) {
                anchors.add(i);
                break;
            }
        }

        // 含代码块的消息
        for (int i = 0; i < total; i++) {
            String content = messages.get(i).getContent();
            if (content != null && content.contains("```")) {
                anchors.add(i);
            }
        }

        return anchors;
    }

    // ====== 句级提取（AGGRESSIVE 模式） ======

    /**
     * 从单条消息中提取关键句子。
     * 保留策略：首句（主题）+ 末句（结论），中间按长度降序填充。
     */
    String extractSentences(Message msg, int maxChars) {
        String content = msg.getContent();
        if (content == null || content.isBlank()) return "";

        String[] parts = content.split("(?<=[。！？.!?\n])\\s*");
        List<String> sentences = new ArrayList<>();
        for (String p : parts) {
            String t = p.trim();
            if (!t.isEmpty()) sentences.add(t);
        }

        if (sentences.size() <= 2) {
            return content.length() <= maxChars ? formatMessage(msg) : "";
        }

        // 首句 + 末句（必须保留）
        String first = sentences.get(0);
        String last = sentences.get(sentences.size() - 1);
        int used = first.length() + last.length() + 4;

        if (used > maxChars) {
            return first.length() <= maxChars ? first : "";
        }

        // 中间句子按长度降序选择
        List<SentenceScore> middle = new ArrayList<>();
        for (int i = 1; i < sentences.size() - 1; i++) {
            middle.add(new SentenceScore(sentences.get(i), sentences.get(i).length()));
        }
        middle.sort((a, b) -> Double.compare(b.score, a.score));

        List<String> kept = new ArrayList<>();
        kept.add(first);
        for (SentenceScore ss : middle) {
            if (used + ss.text.length() <= maxChars) {
                kept.add(ss.text);
                used += ss.text.length();
            }
        }
        kept.add(last);

        String role = msg.isUser() ? "用户" : "助手";
        return role + ": " + String.join(" ", kept) + "\n";
    }

    // ====== 工具方法 ======

    private List<Message> deduplicate(List<Message> messages) {
        if (messages.isEmpty()) return messages;
        List<Message> result = new ArrayList<>();
        result.add(messages.get(0));
        for (int i = 1; i < messages.size(); i++) {
            String prev = messages.get(i - 1).getContent();
            String curr = messages.get(i).getContent();
            if (prev != null && curr != null && prev.equals(curr)) {
                continue;
            }
            result.add(messages.get(i));
        }
        return result;
    }

    private List<Message> removeFiller(List<Message> messages) {
        return messages.stream()
                .filter(m -> !isFiller(m.getContent()))
                .collect(Collectors.toList());
    }

    boolean isFiller(String text) {
        if (text == null || text.isBlank()) return true;
        String t = text.strip();
        if (t.length() > 12) return false;
        String l = t.toLowerCase();
        return l.equals("好的") || l.equals("好的。") || l.equals("好的,") || l.equals("好的，")
                || l.equals("明白") || l.equals("明白了") || l.equals("收到")
                || l.equals("谢谢") || l.equals("谢谢。")
                || l.equals("ok") || l.equals("ok.") || l.equals("ok,")
                || l.equals("嗯") || l.equals("嗯嗯")
                || l.equals("可以") || l.equals("可以。") || l.equals("是的")
                || l.equals("没错") || l.equals("对") || l.equals("好")
                || l.equals("知道了") || l.equals("好的谢谢");
    }

    private UUID findFirstUserMsgId(List<Message> messages) {
        return messages.stream()
                .filter(Message::isUser)
                .map(Message::getMessageId)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    private List<UUID> extractMsgIds(List<Message> messages) {
        return messages.stream()
                .map(Message::getMessageId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    private String formatMessages(List<Message> messages) {
        StringBuilder sb = new StringBuilder();
        for (Message m : messages) sb.append(formatMessage(m));
        return sb.toString().trim();
    }

    String formatMessage(Message msg) {
        String role = msg.isUser() ? "用户" : "助手";
        String content = msg.getContent() != null ? msg.getContent() : "";
        return role + ": " + content + "\n" + (msg.isAssistant() ? "\n" : "");
    }

    // ====== Token 估算 ======

    private int estimateMessagesTokens(List<Message> messages) {
        int total = 0;
        for (Message m : messages) total += estimateMessageTokens(m);
        return total;
    }

    int estimateMessageTokens(Message msg) {
        int tokens = estimateTokens(msg.getContent());
        if (msg.getAttachments() != null) {
            tokens += estimateTokens(msg.getAttachments());
        }
        return tokens;
    }

    int estimateTokens(String text) {
        return PromptContextService.estimateTokens(text);
    }

    // ====== 内部记录 ======

    private record ScoredMsg(Message msg, int index, double score) {}
    private record SentenceScore(String text, double score) {}
}
