package com.icusu.sivan.web.conversation.service.tree;

import com.icusu.sivan.domain.conversation.Message;
import com.icusu.sivan.domain.context.TopicNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 对话话题树，基于 TF-IDF 字符 bi-gram 相似度判定话题边界。
 * <p>
 * 由 {@link ContextBuilder#build} 消费，参与上下文预算分配。
 * 话题边界判定算法（仅用户消息触发判定，助理回复始终追加到当前话题）：
 * <ol>
 *   <li>与当前活跃话题的累积文本（含 user+assistant 全部内容）比较，
 *       TF-IDF 余弦相似度 ≥ {@link #APPEND_THRESHOLD} → 追加到当前话题</li>
 *   <li>否则与所有历史话题的累积文本比较，
 *       存在 ≥ {@link #JUMP_BACK_THRESHOLD} 的 → 跳回</li>
 *   <li>否则创建新话题</li>
 * </ol>
 */
@Component
public class ConversationTree {

    private static final Logger log = LoggerFactory.getLogger(ConversationTree.class);

    /**
     * 追加到当前话题的相似度阈值。
     * 基于字符 bi-gram 的 TF-IDF 余弦相似度范围通常为 0~0.3（中文文本），
     * 此阈值经实测校准：同话题累积文本 vs 续问的相似度约 0.15~0.31，无关话题接近 0。
     */
    private static final double APPEND_THRESHOLD = 0.12;

    /**
     * 跳回历史话题的相似度阈值。
     * 话题 A 累积文本 vs 跳回消息的相似度约 0.25~0.35，无关话题接近 0。
     */
    private static final double JUMP_BACK_THRESHOLD = 0.20;

    /** 话题标签的 Top-K bi-gram 数量 */
    private static final int TOP_TERMS_K = 3;

    private final TfIdfCalculator tfidf = new TfIdfCalculator();

    /**
     * 从消息列表构建话题节点列表（oldest-first）。
     * 每条 user/assistant 消息参与话题判定，使用其文本内容的 TF-IDF 相似度。
     */
    public List<TopicNode> buildTopics(List<Message> messages) {
        if (messages == null || messages.size() < 2) return Collections.emptyList();

        List<TopicNode> topics = new ArrayList<>();
        List<Message> chatMessages = messages.stream()
                .filter(m -> m.isUser() || m.isAssistant())
                .toList();

        // 提取每条消息的文本内容，跳过空白
        List<String> contents = chatMessages.stream()
                .map(Message::getContent)
                .filter(c -> c != null && !c.isBlank())
                .toList();
        if (contents.size() < 2) return Collections.emptyList();

        // 第一条消息：创建初始话题
        Message first = chatMessages.get(0);
        String firstLabel = extractLabel(List.of(first.getContent()));
        TopicNode firstTopic = new TopicNode(firstLabel, first.getMessageId());
        firstTopic.setActive(true);
        topics.add(firstTopic);

        // 逐条判定后续消息的话题归属
        for (int i = 1; i < chatMessages.size(); i++) {
            Message current = chatMessages.get(i);
            String currentText = current.getContent();
            if (currentText == null || currentText.isBlank()) {
                TopicNode active = getActiveTopic(topics);
                active.addMessage(current.getMessageId());
                continue;
            }

            // 助理回复始终追加到当前活跃话题（回复的是当前上下文，不触发话题判定）
            if (current.isAssistant()) {
                TopicNode active = getActiveTopic(topics);
                active.addMessage(current.getMessageId());
                continue;
            }

            TopicNode assigned = assignTopic(currentText, current.getMessageId(), chatMessages.subList(0, i), topics);
            if (assigned == null) {
                // 创建新话题
                String label = extractLabel(List.of(currentText));
                TopicNode newNode = new TopicNode(label, current.getMessageId());
                // 新话题总是活跃的（最近的消息）
                newNode.setActive(true);
                // 旧话题标记为非活跃
                for (TopicNode t : topics) t.setActive(false);
                topics.add(newNode);
            } else {
                assigned.addMessage(current.getMessageId());
                // 跳回的话题标记为活跃
                if (!assigned.isActive()) {
                    for (TopicNode t : topics) t.setActive(false);
                    assigned.setActive(true);
                }
            }
        }

        // 日志输出话题结构（用于验证 TF-IDF 边界判定，不额外查库）
        UUID conversationId = chatMessages.get(0).getConversationId();
        log.info("━━━ 话题结构 ━━━ conversationId={}, 消息数={}, 话题数={}", conversationId, chatMessages.size(), topics.size());
        return topics;
    }

    /** 从话题列表中获取活跃话题，无活跃时取最后一个。 */
    private TopicNode getActiveTopic(List<TopicNode> topics) {
        for (TopicNode t : topics) {
            if (t.isActive()) return t;
        }
        return topics.get(topics.size() - 1);
    }

    /**
     * 为一条消息分配合适的话题节点（仅用户消息触发话题判定）。
     *
     * @return 匹配的话题节点，null 表示需要创建新话题
     */
    private TopicNode assignTopic(String text, UUID msgId, List<Message> precedingMessages, List<TopicNode> topics) {
        if (topics.isEmpty()) return null;

        TopicNode activeTopic = getActiveTopic(topics);

        // 构建消息 ID→对象 映射一次，避免 buildTopicSummary 中重复构造
        Map<UUID, Message> msgMap = new HashMap<>();
        for (Message m : precedingMessages) msgMap.put(m.getMessageId(), m);

        // 步骤 1: 与当前活跃话题的累积文本比较（含多条消息的 bi-gram 更丰富）
        String activeSummary = buildTopicSummary(activeTopic, msgMap);
        if (activeSummary == null || activeSummary.isBlank()) return null;

        double simWithActive = tfidf.similarity(text, activeSummary);
        if (simWithActive >= APPEND_THRESHOLD) {
            return activeTopic;
        }

        // 步骤 2: 与所有其他历史话题的累积文本比较，找最匹配话题跳回
        TopicNode bestMatch = null;
        double bestScore = 0;
        for (TopicNode topic : topics) {
            if (topic == activeTopic) continue;
            String topicSummary = buildTopicSummary(topic, msgMap);
            if (topicSummary == null || topicSummary.isBlank()) continue;

            double sim = tfidf.similarity(text, topicSummary);
            if (sim > bestScore) {
                bestScore = sim;
                bestMatch = topic;
            }
        }

        if (bestMatch != null && bestScore >= JUMP_BACK_THRESHOLD) {
            log.debug("话题跳回: newText='{}', targetTopic={}, score={}",
                    truncate(text, 30), bestMatch.getLabel(), String.format("%.2f", bestScore));
            return bestMatch;
        }

        return null; // 需要创建新话题
    }

    /**
     * 构建话题摘要文本：拼接话题内所有消息的内容。
     *
     * @param msgMap 预先构建的消息 ID→对象 映射，避免每次调用重复构造
     */
    private String buildTopicSummary(TopicNode topic, Map<UUID, Message> msgMap) {
        StringBuilder sb = new StringBuilder();
        for (UUID mid : topic.getMsgIds()) {
            Message m = msgMap.get(mid);
            if (m != null && m.getContent() != null) {
                sb.append(m.getContent()).append(" ");
            }
        }
        return sb.toString().trim();
    }

    /**
     * 从一组文本中提取高频 bi-gram 作为话题标签。
     */
    private String extractLabel(List<String> texts) {
        List<String> terms = tfidf.topTerms(texts, TOP_TERMS_K);
        return terms.isEmpty() ? "unknown" : String.join("/", terms);
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
