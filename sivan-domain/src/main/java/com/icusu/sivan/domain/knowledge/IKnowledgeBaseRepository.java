package com.icusu.sivan.domain.knowledge;

import com.icusu.sivan.domain.shared.vo.Chunk;
import com.icusu.sivan.domain.shared.vo.SearchResult;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 知识库仓储接口。
 */
public interface IKnowledgeBaseRepository {

    // ---- 知识库 ----

    /** 保存知识库。 */
    void save(KnowledgeBase kb);

    /** 根据名称和用户查找知识库。 */
    Optional<KnowledgeBase> findByNameAndAccount(String kbName, UUID accountId);

    /** 获取指定用户的所有知识库。 */
    List<KnowledgeBase> findAllByAccount(UUID accountId);

    /** 根据名称和用户删除知识库。 */
    void deleteByNameAndAccount(String kbName, UUID accountId);

    // ---- 文档 ----

    /** 保存知识库文档。 */
    KbDocument saveDocument(KbDocument document);

    /** 根据 ID 查找文档。 */
    Optional<KbDocument> findDocumentById(UUID docId);

    /** 根据知识库名称查找文档列表。 */
    List<KbDocument> findDocumentsByKb(String kbName, UUID accountId);

    /** 分页查询知识库文档。 */
    List<KbDocument> findDocumentsByKbPage(String kbName, UUID accountId, int page, int size);

    /** 删除文档。 */
    void deleteDocument(UUID docId);

    /** 统计知识库文档数 */
    int countDocumentsByKb(String kbName, UUID accountId);

    /** 统计知识库文档总字符数 */
    int sumCharCountByKb(String kbName, UUID accountId);

    // ---- 向量 ----

    /** 存储文档分块及向量。 */
    void storeChunks(String kbName, UUID accountId, List<Chunk> chunks, List<float[]> vectors);

    /** 执行向量搜索。 */
    List<SearchResult> search(String kbName, UUID accountId, float[] queryVector, int topK);

    /** 执行向量搜索并重排序。 */
    List<SearchResult> searchWithRerank(String kbName, UUID accountId, float[] queryVector, int topK, String queryText);

    /** 跨知识库向量搜索 */
    List<SearchResult> searchAll(UUID accountId, float[] queryVector, int topK);

    /** 跨知识库全文搜索 */
    List<SearchResult> fulltextSearch(UUID accountId, String query, int topK);

    /** 获取文档的所有分块（用于增量索引对比）。 */
    List<Chunk> findChunksByDocId(UUID docId);

    /** 删除文档的所有分块。 */
    void deleteDocumentChunks(String kbName, UUID docId);

    /** 统计知识库的分块总数。 */
    int countChunks(String kbName, UUID accountId);

    /** 重建知识库向量索引。 */
    void rebuildIndex(String kbName, UUID accountId, Consumer<Float> progressCallback);

    /** 批量移动文档到目标知识库（更新 kb_name）。 */
    void moveDocuments(UUID accountId, List<UUID> docIds, String targetKbName);
}
