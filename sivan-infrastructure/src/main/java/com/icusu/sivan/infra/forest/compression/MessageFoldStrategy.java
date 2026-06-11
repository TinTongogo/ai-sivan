package com.icusu.sivan.infra.forest.compression;

import com.icusu.sivan.domain.compression.FoldDecision;
import com.icusu.sivan.domain.compression.FoldStrategy;
import com.icusu.sivan.domain.compression.TokenBudget;
import com.icusu.sivan.domain.forest.tree.ContentNode;
import com.icusu.sivan.domain.forest.tree.TreeNode;
import org.springframework.stereotype.Component;

/**
 * MessageNode 折叠策略。
 * <p>
 * 历史消息超过预算 → 折叠为 LLM 摘要格式。
 */
@Component
public class MessageFoldStrategy implements FoldStrategy {

    /** 当单条消息超过此字符数时认为需要摘要 */
    private static final int SUMMARY_THRESHOLD = 500;

    @Override
    public String supportedType() {
        return "message";
    }

    @Override
    public FoldDecision decide(TreeNode node, TokenBudget budget) {
        if (!(node instanceof ContentNode cn)) {
            return FoldDecision.skip("不是 ContentNode");
        }

        String content = cn.content();
        if (content == null || content.isBlank()) {
            return FoldDecision.skip("内容为空");
        }

        int length = content.length();
        int messageBudget = budget.forType("message");

        // 超出预算 → 折叠为摘要摘要
        long tokens = (long) (length * 1.5);
        if (tokens > messageBudget && messageBudget > 0) {
            String summary = summarize(content);
            return FoldDecision.fold("[摘要] " + summary);
        }

        // 过长消息 → 折叠
        if (length > SUMMARY_THRESHOLD) {
            String summary = summarize(content);
            return FoldDecision.fold("[摘要] " + summary);
        }

        return FoldDecision.skip("预算充足");
    }

    /** 取前 100 字作为摘要（占位，后续可替换为 LLM 摘要）。 */
    private static String summarize(String content) {
        String plain = content.replaceAll("\\s+", " ").trim();
        int end = Math.min(plain.length(), 100);
        return plain.substring(0, end) + (end < plain.length() ? "..." : "");
    }
}
