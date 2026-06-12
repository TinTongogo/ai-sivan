package com.icusu.sivan.domain.knowledge;

/**
 * 分块策略枚举（10-知识库与RAG §6.2）。
 */
public enum ChunkStrategy {
    AUTO,        // 自动检测格式选择最佳分块
    MARKDOWN,    // 按 Markdown 标题分块
    SEMANTIC,    // 按语义边界分块
    FIXED_SIZE   // 按固定大小分块
}
