package com.icusu.sivan.application.conversation.tree;

import com.icusu.sivan.domain.memory.MemoryEntry;
import com.icusu.sivan.domain.context.ContextTree;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 记忆树，管理跨会话长期记忆的上下文折叠。
 * <p>
 * important=true 的记忆保留原文，其余按重要性/访问频次折叠为一行。
 */
public class MemoryTree implements ContextTree {

    private static final Logger log = LoggerFactory.getLogger(MemoryTree.class);

    private List<MemoryEntry> userMemories;
    private int cachedTokens;

    public MemoryTree() {
        this.cachedTokens = 0;
    }

    /** 注入用户长期记忆列表。 */
    public MemoryTree withMemories(List<MemoryEntry> userMemories) {
        this.userMemories = userMemories;
        this.cachedTokens = 0;
        return this;
    }

    @Override
    public String treeType() {
        return "memory";
    }

    @Override
    public String buildContext(String scene, int maxTokens) {
        if (userMemories == null || userMemories.isEmpty()) return "";

        try {
            StringBuilder sb = new StringBuilder();
            sb.append("## 用户长期记忆\n\n");

            int budget = 0;
            // 重要记忆：保留原文
            for (MemoryEntry entry : userMemories) {
                boolean important = entry.getImportant() != null && entry.getImportant();
                String text = important ? entry.getContent() : entry.getSummary();
                if (text == null || text.isBlank()) continue;

                int tokens = estimateTokenCount(text);
                if (budget + tokens > maxTokens) {
                    if (important) {
                        // 重要记忆即使超预算也要保留摘要
                        String summary = entry.getSummary();
                        if (summary != null && !summary.isBlank()) {
                            int summaryTokens = estimateTokenCount(summary);
                            if (budget + summaryTokens <= maxTokens) {
                                sb.append("- [重要] ").append(summary).append("\n");
                                budget += summaryTokens;
                            }
                        }
                    }
                    continue;
                }

                if (important) {
                    sb.append("- [重要] ").append(text).append("\n");
                } else {
                    sb.append("- ").append(truncateStr(text, 100)).append("\n");
                }
                budget += tokens;
            }

            String result = sb.toString().trim();
            cachedTokens = estimateTokenCount(result);
            return result;

        } catch (Exception e) {
            log.warn("MemoryTree 构建异常", e);
            return "";
        }
    }

    @Override
    public int estimateTokens() {
        return cachedTokens;
    }

    private static int estimateTokenCount(String text) {
        if (text == null || text.isEmpty()) return 0;
        return (int) Math.ceil(text.length() / 2.0);
    }

    private static String truncateStr(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
