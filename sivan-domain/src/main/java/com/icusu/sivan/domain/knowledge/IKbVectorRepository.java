package com.icusu.sivan.domain.knowledge;

import com.icusu.sivan.domain.shared.vo.Chunk;
import com.icusu.sivan.domain.shared.vo.SearchResult;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 知识库向量存储与搜索仓储接口。
 */
public interface IKbVectorRepository {

    void storeChunks(String kbName, UUID accountId, List<Chunk> chunks, List<float[]> vectors);

    List<SearchResult> search(String kbName, UUID accountId, float[] queryVector, int topK);

    List<SearchResult> searchWithRerank(String kbName, UUID accountId, float[] queryVector, int topK, String queryText);

    List<SearchResult> searchAll(UUID accountId, float[] queryVector, int topK);

    List<SearchResult> fulltextSearch(UUID accountId, String query, int topK);

    void deleteDocumentChunks(String kbName, UUID docId);

    int countChunks(String kbName, UUID accountId);

    void rebuildIndex(String kbName, UUID accountId, Consumer<Float> progressCallback);
}
