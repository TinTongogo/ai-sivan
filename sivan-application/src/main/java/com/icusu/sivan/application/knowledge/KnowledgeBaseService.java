package com.icusu.sivan.application.knowledge;

import com.icusu.sivan.common.dto.PageResponse;
import com.icusu.sivan.domain.knowledge.KbDocument;
import com.icusu.sivan.domain.shared.vo.Chunk;
import com.icusu.sivan.application.knowledge.dto.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * 知识库服务 — 委托层，实际逻辑在 {@link KbCrudService} 和 {@link KbSearchService} 中。
 * <p>
 * 保持此类对外接口不変，避免影响已有调用方。新代码请直接使用两个子服务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseService {

    private final KbCrudService kbCrudService;
    private final KbSearchService kbSearchService;

    // ===== 委托给 KbCrudService =====

    public KnowledgeBaseResponse create(UUID accountId, CreateKnowledgeBaseRequest request) {
        return kbCrudService.create(accountId, request);
    }

    public KnowledgeBaseResponse getByName(UUID accountId, String kbName) {
        return kbCrudService.getByName(accountId, kbName);
    }

    public List<KnowledgeBaseResponse> list(UUID accountId) {
        return kbCrudService.list(accountId);
    }

    public KnowledgeBaseResponse update(UUID accountId, String kbName, UpdateKnowledgeBaseRequest request) {
        return kbCrudService.update(accountId, kbName, request);
    }

    public void delete(UUID accountId, String kbName) {
        kbCrudService.delete(accountId, kbName);
    }

    public List<KbDocumentResponse> listDocuments(UUID accountId, String kbName) {
        return kbCrudService.listDocuments(accountId, kbName);
    }

    public PageResponse<KbDocumentResponse> listDocumentsPage(UUID accountId, String kbName, int page, int size) {
        return kbCrudService.listDocumentsPage(accountId, kbName, page, size);
    }

    public KbDocumentResponse getDocument(UUID docId, UUID accountId) {
        return kbCrudService.getDocument(docId, accountId);
    }

    public KbDocumentResponse createDocument(UUID accountId, String kbName, String filename, String textContent) {
        return kbCrudService.createDocument(accountId, kbName, filename, textContent);
    }

    public KbDocumentResponse updateDocument(UUID accountId, UUID docId, String filename, String textContent) {
        return kbCrudService.updateDocument(accountId, docId, filename, textContent);
    }

    public void deleteDocument(UUID docId, UUID accountId) {
        kbCrudService.deleteDocument(docId, accountId);
    }

    public void reindexDocument(UUID docId, UUID accountId) {
        kbCrudService.reindexDocument(docId, accountId);
    }

    public List<Chunk> getDocumentChunks(UUID docId, UUID accountId) {
        return kbCrudService.getDocumentChunks(docId, accountId);
    }

    public KbDocumentResponse uploadDocument(UUID accountId, String kbName, String filename, String textContent) {
        return kbCrudService.uploadDocument(accountId, kbName, filename, textContent);
    }

    public KbDocumentResponse uploadImageDocument(UUID accountId, String kbName, String filename, UUID fileId, String mimeType) {
        return kbCrudService.uploadImageDocument(accountId, kbName, filename, fileId, mimeType);
    }

    public void rebuildIndex(UUID accountId, String kbName) {
        kbCrudService.rebuildIndex(accountId, kbName);
    }

    public void rebuildAllIndexes(UUID accountId) {
        kbCrudService.rebuildAllIndexes(accountId);
    }

    public void moveDocuments(UUID accountId, String sourceKbName, List<UUID> docIds, String targetKbName) {
        kbCrudService.moveDocuments(accountId, sourceKbName, docIds, targetKbName);
    }

    public byte[] exportDocuments(UUID accountId, String kbName, List<UUID> docIds) {
        return kbCrudService.exportDocuments(accountId, kbName, docIds);
    }

    public List<KbOverviewResponse> getOverview(UUID accountId) {
        return kbCrudService.getOverview(accountId);
    }

    // ===== 委托给 KbSearchService =====

    public List<SearchResultResponse> search(UUID accountId, String kbName, SearchRequest request) {
        return kbSearchService.search(accountId, kbName, request);
    }

    public List<SearchResultResponse> searchAll(UUID accountId, SearchRequest request) {
        return kbSearchService.searchAll(accountId, request);
    }
}
