package com.icusu.sivan.web.conversation.service.tree;

import com.icusu.sivan.domain.context.ContextTree;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 工具链树，管理工具调用历史记录的上下文折叠。
 * <p>
 * 当前活跃话题相关的工具结果保留原文，
 * 非活跃话题或旧工具调用折叠为一行摘要。
 */
public class ToolChainTree implements ContextTree {

    private static final Logger log = LoggerFactory.getLogger(ToolChainTree.class);

    private List<String> toolCallRecords;
    private int cachedTokens;

    public ToolChainTree() {
        this.cachedTokens = 0;
    }

    /** 注入工具调用记录列表（每元素为已格式化的工具调用文本行）。 */
    public ToolChainTree withToolCalls(List<String> toolCallRecords) {
        this.toolCallRecords = toolCallRecords;
        this.cachedTokens = 0;
        return this;
    }

    @Override
    public String treeType() {
        return "toolchain";
    }

    @Override
    public String buildContext(String scene, int maxTokens) {
        if (toolCallRecords == null || toolCallRecords.isEmpty()) return "";

        try {
            StringBuilder sb = new StringBuilder();
            sb.append("## 工具调用记录\n\n");

            int budget = 0;
            int total = toolCallRecords.size();

            for (int i = total - 1; i >= 0; i--) {
                String record = toolCallRecords.get(i);
                if (record == null || record.isBlank()) continue;

                int tokens = estimateTokenCount(record);
                if (budget + tokens > maxTokens) {
                    // 最新工具记录优先保留，旧记录折叠
                    sb.append("- ... 及其他 ").append(i + 1).append(" 个工具调用\n");
                    break;
                }

                // 最新几条展开，旧的全部折叠
                if (i >= total - 3) {
                    sb.append("- ").append(record).append("\n");
                } else {
                    sb.append("- ").append(truncateStr(record, 80)).append("\n");
                }
                budget += tokens;
            }

            String result = sb.toString().trim();
            cachedTokens = estimateTokenCount(result);
            return result;

        } catch (Exception e) {
            log.warn("ToolChainTree 构建异常", e);
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
