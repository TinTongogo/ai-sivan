package com.icusu.sivan.domain.conversation;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 消息评分器 — 基于角色、位置、内容信号、关键词匹配和时间衰减的综合评分。
 * <p>
 * 零外部依赖，纯业务规则。用于压缩/提取场景中判断消息重要性。
 */
public class MessageScorer {

    /** 消息评分。 */
    public record ScoredMessage(Message msg, int index, double score) {}

    /** 句子评分。 */
    public record ScoredSentence(String text, double score) {}

    /**
     * 对单条消息进行综合评分。
     *
     * @param msg   消息
     * @param index 在消息列表中的位置
     * @param total 消息总数
     * @param query 用户当前查询（用于关键词匹配，可为 null）
     * @return 评分值
     */
    public double score(Message msg, int index, int total, String query) {
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

        // 时间衰减：越近的消息越重要（30 天衰减到 10%）
        if (msg.getCreatedAt() != null) {
            var hoursAge = Duration.between(msg.getCreatedAt(), LocalDateTime.now()).toHours();
            double timeFactor = Math.max(0.1, 1.0 - hoursAge / 720.0);
            score *= timeFactor;
        }

        return score;
    }

    /**
     * 为消息列表批量评分，降序排列。
     */
    public List<ScoredMessage> scoreAll(List<Message> messages, String query) {
        int total = messages.size();
        List<ScoredMessage> result = new ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            double s = score(messages.get(i), i, total, query);
            result.add(new ScoredMessage(messages.get(i), i, s));
        }
        result.sort((a, b) -> Double.compare(b.score, a.score));
        return result;
    }

    /**
     * 识别必须保留的结构性锚点消息：
     * <ul>
     *   <li>第一条消息（对话起始）</li>
     *   <li>第一条用户消息（任务目标）</li>
     *   <li>最后一条用户消息（当前请求）</li>
     *   <li>含代码块的消息（高信息密度）</li>
     * </ul>
     */
    public Set<Integer> findAnchors(List<Message> messages, UUID taskGoalMsgId) {
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

    /**
     * 从单条消息中提取关键句子。
     * 保留策略：首句（主题）+ 末句（结论），中间按长度降序填充。
     */
    public String extractSentences(Message msg, int maxChars) {
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
        List<ScoredSentence> middle = new ArrayList<>();
        for (int i = 1; i < sentences.size() - 1; i++) {
            middle.add(new ScoredSentence(sentences.get(i), sentences.get(i).length()));
        }
        middle.sort((a, b) -> Double.compare(b.score, a.score));

        List<String> kept = new ArrayList<>();
        kept.add(first);
        for (ScoredSentence ss : middle) {
            if (used + ss.text().length() <= maxChars) {
                kept.add(ss.text());
                used += ss.text().length();
            }
        }
        kept.add(last);

        String role = msg.isUser() ? "用户" : "助手";
        return role + ": " + String.join(" ", kept) + "\n";
    }

    /**
     * 判断文本是否为无意义的填充语。
     */
    public boolean isFiller(String text) {
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

    /** 格式化单条消息。 */
    private String formatMessage(Message msg) {
        String role = msg.isUser() ? "用户" : "助手";
        String content = msg.getContent() != null ? msg.getContent() : "";
        return role + ": " + content + "\n" + (msg.isAssistant() ? "\n" : "");
    }
}
