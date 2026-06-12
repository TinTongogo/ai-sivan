package com.icusu.sivan.domain.knowledge;

/**
 * 检索策略配置（10-知识库与RAG §6.2）。
 *
 * @param expandQuery         是否启用查询改写
 * @param topK                检索返回条数（默认 5）
 * @param rerankTopK          重排后返回条数（默认 3）
 * @param similarityThreshold 相似度阈值（默认 0.65）
 * @param rerankerModel       重排模型名称（null 表示不启用）
 * @param chunkStrategy       分块策略
 */
public record RetrievalConfig(
        boolean expandQuery,
        int topK,
        int rerankTopK,
        double similarityThreshold,
        String rerankerModel,
        ChunkStrategy chunkStrategy
) {
    public static RetrievalConfig defaults() {
        return new RetrievalConfig(true, 5, 3, 0.65, null, ChunkStrategy.AUTO);
    }
}
