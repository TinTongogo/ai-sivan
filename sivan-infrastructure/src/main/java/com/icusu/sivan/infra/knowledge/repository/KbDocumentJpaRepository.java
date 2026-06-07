package com.icusu.sivan.infra.knowledge.repository;

import com.icusu.sivan.infra.knowledge.entity.KbDocumentEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/**
 * 知识库文档表数据访问接口。
 */
@Repository
public interface KbDocumentJpaRepository extends JpaRepository<KbDocumentEntity, UUID> {

    List<KbDocumentEntity> findByKbNameAndAccountId(String kbName, UUID accountId);

    Page<KbDocumentEntity> findByKbNameAndAccountId(String kbName, UUID accountId, Pageable pageable);

    void deleteByKbNameAndAccountId(String kbName, UUID accountId);

    int countByKbNameAndAccountId(String kbName, UUID accountId);

    /** Sum char_count for a specific knowledge base */
    @org.springframework.data.jpa.repository.Query(
            "SELECT COALESCE(SUM(d.charCount), 0) FROM KbDocumentEntity d WHERE d.kbName = :kbName AND d.accountId = :accountId")
    int sumCharCountByKbNameAndAccountId(@org.springframework.data.repository.query.Param("kbName") String kbName,
                                         @org.springframework.data.repository.query.Param("accountId") UUID accountId);

    /** Batch move documents to another knowledge base (update kbName). */
    @Modifying
    @Query("UPDATE KbDocumentEntity d SET d.kbName = :targetKbName WHERE d.docId IN :docIds AND d.accountId = :accountId")
    int batchMoveDocuments(@Param("docIds") List<UUID> docIds,
                           @Param("targetKbName") String targetKbName,
                           @Param("accountId") UUID accountId);
}
