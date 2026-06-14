package com.icusu.sivan.infra.knowledge.repository;

import com.icusu.sivan.infra.knowledge.entity.KbVectorEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import org.springframework.data.jpa.repository.Modifying;

import java.util.List;
import java.util.UUID;

/**
 * kb_vectors 表数据访问接口。
 */
@Repository
public interface KbVectorJpaRepository extends JpaRepository<KbVectorEntity, Long> {

    List<KbVectorEntity> findByKbNameAndAccountIdAndIsDeletedFalse(String kbName, UUID accountId);

    List<KbVectorEntity> findByDocIdAndIsDeletedFalse(UUID docId);

    void deleteByDocId(UUID docId);

    int countByKbNameAndAccountIdAndIsDeletedFalse(String kbName, UUID accountId);

    /**
     * 向量相似度搜索（余弦距离）。
     */
    @Query(value = "SELECT id, kb_name, account_id, chunk_id, doc_id, text_content, content_type, image_path, metadata, " +
           "1 - (vector <=> CAST(:queryVector AS vector)) AS similarity_score " +
           "FROM kb_vectors WHERE kb_name = :kbName AND account_id = :accountId AND is_deleted = false " +
           "ORDER BY vector <=> CAST(:queryVector AS vector) ASC LIMIT :topK", nativeQuery = true)
    List<Object[]> vectorSearch(@Param("kbName") String kbName, @Param("accountId") UUID accountId,
                                @Param("queryVector") String queryVector, @Param("topK") int topK);

    /**
     * 跨知识库向量搜索（不限定 kbName）。
     */
    @Query(value = "SELECT id, kb_name, account_id, chunk_id, doc_id, text_content, content_type, image_path, metadata, " +
           "1 - (vector <=> CAST(:queryVector AS vector)) AS similarity_score " +
           "FROM kb_vectors WHERE account_id = :accountId AND is_deleted = false " +
           "ORDER BY vector <=> CAST(:queryVector AS vector) ASC LIMIT :topK", nativeQuery = true)
    List<Object[]> vectorSearchAll(@Param("accountId") UUID accountId,
                                    @Param("queryVector") String queryVector, @Param("topK") int topK);

    /**
     * 全文检索（跨知识库），使用 pg_trgm 相似度匹配（支持中文）。
     * 需要 pg_trgm 扩展 + GIN 索引 (gin_trgm_ops) 支撑性能。
     */
    @Query(value = "SELECT id, kb_name, account_id, chunk_id, doc_id, text_content, content_type, image_path, metadata, " +
           "similarity(text_content, :query) AS relevance_score " +
           "FROM kb_vectors WHERE account_id = :accountId AND is_deleted = false " +
           "AND text_content ILIKE '%' || :query || '%' " +
           "ORDER BY relevance_score DESC, id DESC LIMIT :topK", nativeQuery = true)
    List<Object[]> fulltextSearch(@Param("accountId") UUID accountId,
                                   @Param("query") String query, @Param("topK") int topK);

    /** Batch move vector chunks to another knowledge base (update kbName by docIds). */
    @Modifying
    @Query("UPDATE KbVectorEntity v SET v.kbName = :targetKbName WHERE v.docId IN :docIds AND v.accountId = :accountId")
    int batchMoveVectors(@Param("docIds") List<UUID> docIds,
                         @Param("targetKbName") String targetKbName,
                         @Param("accountId") UUID accountId);

    /** 批量软删除知识库所有分块（重建索引用）。 */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE KbVectorEntity v SET v.isDeleted = true WHERE v.kbName = :kbName AND v.accountId = :accountId")
    int softDeleteAllByKb(@Param("kbName") String kbName, @Param("accountId") UUID accountId);
}
