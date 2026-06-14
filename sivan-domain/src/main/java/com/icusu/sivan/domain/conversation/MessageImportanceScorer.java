package com.icusu.sivan.domain.conversation;

import com.icusu.sivan.domain.conversation.Message;
import com.icusu.sivan.domain.shared.port.IEmbeddingService;
import com.icusu.sivan.common.util.CosineSimilarity;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 消息重要性评估 — 决定哪些消息优先保留、哪些可以被压缩。
 * <p>
 * 设计文档 4.2 节。分数 [0, 1]，1=必须保留，0=可完全丢弃。
 * <p>
 * 决策检测策略：
 * <ol>
 *   <li><b>快速关键词匹配</b> — 显式决策词、隐式确认短回复 + AI 建议上下文</li>
 *   <li><b>Embedding 语义匹配</b> — 与已知决策模式库做余弦相似度，
 *       覆盖关键词无法穷举的表达（"这个方案不可行"、"按方案A处理"）</li>
 * </ol>
 */
public class MessageImportanceScorer {

    // 显式决策关键词
    private static final Pattern EXPLICIT_DECISION = Pattern.compile(
            "决定|选择|采用|decided|chosen|adopt",
            Pattern.CASE_INSENSITIVE
    );

    // 用户短确认回复模式（隐式决策）
    private static final Pattern CONFIRM_SHORT_REPLY = Pattern.compile(
            "^(好的|行|可以|继续|确认|同意|赞成|批准|就这|就这样|按此|没问题|收到"
            + "|ok|yes|继续处理|继续执行|按你说的|就按|用这个|试试"
            + "|好|嗯|要|不要|算了|放弃|停下|停止|行吧)$",
            Pattern.CASE_INSENSITIVE
    );

    // AI 建议/选项提示词
    private static final Pattern AI_CHOICE_PATTERN = Pattern.compile(
            "(建议|推荐|选择|选项|方案[一二三123]|你觉得|要不要|是否|可以吗"
            + "|如何|怎么样|行不行|同意|批准)",
            Pattern.CASE_INSENSITIVE
    );

    // ── Embedding 语义决策库 ──
    // 与这些基准表达语义相似的用户消息视为决策
    private static final String[] DECISION_PROTOTYPES = {
            "我决定采用这个方案",
            "选择第一个选项",
            "同意这个方案，继续执行",
            "这个方案不行，换一个",
            "按你说的方式处理",
            "用方案B",
            "先试试这个方案",
            "放弃当前方案",
            "停止执行",
            "继续按这个方向走",
    };

    private final IEmbeddingService embeddingService;
    private float[][] decisionPrototypeEmbeddings;  // 惰性计算缓存

    public MessageImportanceScorer(IEmbeddingService embeddingService) {
        this.embeddingService = embeddingService;
    }

    public MessageImportanceScorer() {
        this.embeddingService = null;  // 仅测试用，语义匹配降级为关键词
    }

    public double score(Message msg) {
        return scoreWithContext(msg, null);
    }

    public double scoreWithContext(Message msg, Message prevMessage) {
        if (msg == null || msg.getContent() == null) return 0.0;
        String content = msg.getContent();
        double score = 0.3;

        if ("user".equals(msg.getRole())) score += 0.1;
        if ("assistant".equals(msg.getRole())
                && msg.getAttachments() != null
                && !msg.getAttachments().isBlank()) score += 0.1;

        if (isDecision(content, prevMessage)) score += 0.3;
        if (containsCode(content)) score += 0.2;
        if (containsUrl(content)) score += 0.1;

        if (content.length() < 10) score -= 0.2;

        if (msg.getCreatedAt() != null) {
            double hoursAge = ChronoUnit.HOURS.between(msg.getCreatedAt(), LocalDateTime.now());
            score *= Math.max(0.1, 1.0 - hoursAge / 720);
        }

        return Math.max(0, Math.min(1, score));
    }

    public double[] scoreAll(List<Message> messages) {
        if (messages == null || messages.isEmpty()) return new double[0];
        double[] scores = new double[messages.size()];
        for (int i = 0; i < messages.size(); i++) {
            scores[i] = scoreWithContext(messages.get(i), i > 0 ? messages.get(i - 1) : null);
        }
        return scores;
    }

    /**
     * 判断消息是否包含决策语义。
     * 三层检测：关键词 → 上下文 → Embedding 语义。
     */
    boolean isDecision(String text, Message prevMessage) {
        if (text == null || text.isBlank()) return false;
        String trimmed = text.trim();

        // L1: 显式决策关键词
        if (EXPLICIT_DECISION.matcher(trimmed).find()) return true;

        // L2: 隐式决策 — 用户短确认 + AI 含建议
        String prevContent = prevMessage != null ? prevMessage.getContent() : null;
        if (CONFIRM_SHORT_REPLY.matcher(trimmed).matches()
                && prevContent != null
                && AI_CHOICE_PATTERN.matcher(prevContent).find()) {
            return true;
        }

        // L3: Embedding 语义匹配（降级到关键词时跳过）
        if (embeddingService != null && trimmed.length() >= 4) {
            try {
                float[] vec = embeddingService.embed(trimmed);
                if (vec != null) {
                    initPrototypeEmbeddings();
                    for (float[] proto : decisionPrototypeEmbeddings) {
                        if (CosineSimilarity.compute(vec, proto) > 0.75) {
                            return true;
                        }
                    }
                }
            } catch (Exception e) {
                // embedding 失败不影响主流程
            }
        }

        return false;
    }

    private void initPrototypeEmbeddings() {
        if (decisionPrototypeEmbeddings != null) return;
        decisionPrototypeEmbeddings = new float[DECISION_PROTOTYPES.length][];
        for (int i = 0; i < DECISION_PROTOTYPES.length; i++) {
            try {
                decisionPrototypeEmbeddings[i] = embeddingService.embed(DECISION_PROTOTYPES[i]);
            } catch (Exception e) {
                decisionPrototypeEmbeddings[i] = new float[0];
            }
        }
    }

    private boolean containsCode(String text) {
        return text.contains("```") || text.contains("public ") || text.contains("function")
                || text.contains("def ") || text.contains("class ") || text.contains("import ");
    }

    private boolean containsUrl(String text) {
        return text.contains("http://") || text.contains("https://");
    }
}
