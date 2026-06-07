package com.icusu.sivan.domain.knowledge;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 知识库文档仓储接口。
 */
public interface IKbDocumentRepository {

    KbDocument saveDocument(KbDocument document);

    Optional<KbDocument> findDocumentById(UUID docId);

    List<KbDocument> findDocumentsByKb(String kbName, UUID accountId);

    List<KbDocument> findDocumentsByKbPage(String kbName, UUID accountId, int page, int size);

    void deleteDocument(UUID docId);

    int countDocumentsByKb(String kbName, UUID accountId);

    int sumCharCountByKb(String kbName, UUID accountId);

    void moveDocuments(UUID accountId, List<UUID> docIds, String targetKbName);
}
