package com.icusu.sivan.domain.knowledge;

/**
 * 知识库索引状态（10-知识库与RAG §6.3）。
 */
public enum IndexStatus {
    COMPLETE,  // 所有文档已成功索引
    PARTIAL,   // 部分文档索引成功，部分失败
    BUILDING,  // 正在索引中
    STALE      // 文档已更新但未重新索引
}
