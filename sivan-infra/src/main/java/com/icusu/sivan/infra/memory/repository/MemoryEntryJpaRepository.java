package com.icusu.sivan.infra.memory.repository;

import com.icusu.sivan.infra.memory.entity.MemoryEntryEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 记忆条目表数据访问接口。
 */
@Repository
public interface MemoryEntryJpaRepository extends JpaRepository<MemoryEntryEntity, UUID> {

    Optional<MemoryEntryEntity> findByMemoryIdAndAccountId(UUID memoryId, UUID accountId);

    @Query("SELECT m FROM MemoryEntryEntity m WHERE m.accountId = :accountId AND m.level = :level AND m.scopeId = :scopeId ORDER BY m.createdAt ASC")
    List<MemoryEntryEntity> findByAccountIdAndLevelAndScopeId(@Param("accountId") UUID accountId, @Param("level") String level, @Param("scopeId") String scopeId);

    List<MemoryEntryEntity> findByAccountId(UUID accountId);

    Page<MemoryEntryEntity> findByAccountId(UUID accountId, Pageable pageable);

    Page<MemoryEntryEntity> findByAccountIdAndLevel(UUID accountId, String level, Pageable pageable);

    long countByAccountId(UUID accountId);

    long countByAccountIdAndLevel(UUID accountId, String level);

    long countByAccountIdAndImportantTrue(UUID accountId);

    long countByAccountIdAndArchivedTrue(UUID accountId);

    List<MemoryEntryEntity> findByAccountIdAndArchivedFalseAndRetentionLessThan(UUID accountId, Float threshold);

    List<MemoryEntryEntity> findByArchivedFalse();

    List<MemoryEntryEntity> findByAccountIdAndImportantTrueAndProjectId(UUID accountId, UUID projectId);

    @Query("SELECT m FROM MemoryEntryEntity m WHERE m.accountId = :accountId AND m.level = :level AND m.archived = false ORDER BY m.createdAt ASC")
    List<MemoryEntryEntity> findByAccountIdAndLevelAndArchivedFalse(@Param("accountId") UUID accountId, @Param("level") String level);

    /** 关键词搜索（keyword 中的 % 和 _ 已由调用方转义，ESCAPE '\\' 防止通配符注入）。 */
    @Query("SELECT m FROM MemoryEntryEntity m WHERE m.accountId = :accountId AND (LOWER(m.content) LIKE LOWER(CONCAT('%', :keyword, '%')) ESCAPE '\\' OR LOWER(m.summary) LIKE LOWER(CONCAT('%', :keyword, '%')) ESCAPE '\\')")
    Page<MemoryEntryEntity> searchByKeyword(@Param("accountId") UUID accountId, @Param("keyword") String keyword, Pageable pageable);

    long countByAccountIdAndContentContainingIgnoreCaseOrSummaryContainingIgnoreCase(UUID accountId, String keywordContent, String keywordSummary);

    /**
     * 向量余弦相似度搜索（cosine distance），按距离升序取 topK。
     * 排除已归档条目。
     */
    @Query(value = "SELECT * FROM memory_entries WHERE account_id = :accountId AND level = :level AND is_archived = false ORDER BY vector <=> CAST(:queryVector AS vector) LIMIT :topK", nativeQuery = true)
    List<MemoryEntryEntity> semanticSearch(@Param("accountId") UUID accountId, @Param("level") String level, @Param("queryVector") String queryVector, @Param("topK") int topK);

}
