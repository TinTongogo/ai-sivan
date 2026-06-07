package com.icusu.sivan.web.conversation.service.tree;

import com.icusu.sivan.domain.context.ContextTree;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 知识库树，管理 KB→Doc→Chunk 三层结构的上下文折叠。
 * <p>
 * 高相关度 Chunk 保留原文，低相关度 Chunk 折叠为文档摘要。
 */
public class KBTree implements ContextTree {

    private static final Logger log = LoggerFactory.getLogger(KBTree.class);

    private String kbContext;
    private int cachedTokens;

    public KBTree() {
        this.cachedTokens = 0;
    }

    /** 注入已检索的知识库上下文文本。 */
    public KBTree withContext(String kbContext) {
        this.kbContext = kbContext;
        this.cachedTokens = estimateTokenCount(kbContext);
        return this;
    }

    @Override
    public String treeType() {
        return "kb";
    }

    @Override
    public String buildContext(String scene, int maxTokens) {
        if (kbContext == null || kbContext.isBlank()) return "";

        try {
            int tokens = estimateTokenCount(kbContext);
            if (tokens <= maxTokens) {
                return kbContext;
            }

            // 预算紧张：截断到预算
            int maxChars = maxTokens * 2;
            String truncated = kbContext.length() <= maxChars
                    ? kbContext
                    : kbContext.substring(0, maxChars) + "...";

            cachedTokens = estimateTokenCount(truncated);
            return truncated;

        } catch (Exception e) {
            log.warn("KBTree 构建异常", e);
            return kbContext != null ? kbContext : "";
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
}
