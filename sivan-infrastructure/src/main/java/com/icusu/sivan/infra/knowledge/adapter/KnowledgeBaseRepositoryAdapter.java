package com.icusu.sivan.infra.knowledge.adapter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.icusu.sivan.domain.knowledge.KbDocument;
import com.icusu.sivan.domain.knowledge.KnowledgeBase;
import com.icusu.sivan.domain.knowledge.IKnowledgeBaseCrudRepository;
import com.icusu.sivan.domain.knowledge.IKnowledgeBaseRepository;
import com.icusu.sivan.domain.knowledge.IKbDocumentRepository;
import com.icusu.sivan.domain.knowledge.IKbVectorRepository;
import com.icusu.sivan.domain.shared.vo.Chunk;
import com.icusu.sivan.infra.knowledge.entity.KbVectorEntity;
import com.icusu.sivan.domain.shared.vo.SearchResult;
import com.icusu.sivan.infra.knowledge.RerankerService;
import com.icusu.sivan.infra.knowledge.entity.KbDocumentEntity;
import com.icusu.sivan.infra.knowledge.entity.KnowledgeBaseEntity;
import com.icusu.sivan.infra.knowledge.repository.KbDocumentJpaRepository;
import com.icusu.sivan.infra.knowledge.repository.KbVectorJpaRepository;
import com.icusu.sivan.infra.knowledge.repository.KnowledgeBaseJpaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.TimeZone;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 知识库仓储适配器，实现 IKnowledgeBaseRepository。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class KnowledgeBaseRepositoryAdapter implements IKnowledgeBaseRepository,
        IKnowledgeBaseCrudRepository, IKbDocumentRepository, IKbVectorRepository {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .setTimeZone(TimeZone.getTimeZone("UTC"));

    private final KnowledgeBaseJpaRepository knowledgeBaseJpaRepository;
    private final KbDocumentJpaRepository kbDocumentJpaRepository;
    private final KbVectorJpaRepository kbVectorJpaRepository;
    private final RerankerService rerankerService;

    /** 保存知识库。 */
    @Override
    public void save(KnowledgeBase kb) {
        KnowledgeBaseEntity entity = toEntity(kb);
        knowledgeBaseJpaRepository.save(entity);
        if (kb.getCreatedAt() == null) {
            kb.setCreatedAt(entity.getCreatedAt() != null ? entity.getCreatedAt().toLocalDateTime() : null);
        }
        kb.setUpdatedAt(entity.getUpdatedAt() != null ? entity.getUpdatedAt().toLocalDateTime() : null);
    }

    /** 根据名称和账号查询知识库。 */
    @Override
    public Optional<KnowledgeBase> findByNameAndAccount(String kbName, UUID accountId) {
        return knowledgeBaseJpaRepository.findByKbNameAndAccountId(kbName, accountId)
                .map(this::toDomain);
    }

    /** 查询账号下所有知识库。 */
    @Override
    public List<KnowledgeBase> findAllByAccount(UUID accountId) {
        return knowledgeBaseJpaRepository.findByAccountId(accountId).stream()
                .map(this::toDomain).toList();
    }

    /** 根据名称和账号删除知识库。 */
    @Override
    public void deleteByNameAndAccount(String kbName, UUID accountId) {
        knowledgeBaseJpaRepository.deleteByKbNameAndAccountId(kbName, accountId);
    }

    /** 保存知识库文档。 */
    @Override
    public KbDocument saveDocument(KbDocument document) {
        KbDocumentEntity entity = toEntity(document);
        kbDocumentJpaRepository.save(entity);
        if (document.getDocId() == null) {
            document.setDocId(entity.getDocId());
        }
        document.setCreatedAt(entity.getCreatedAt() != null ? entity.getCreatedAt().toLocalDateTime() : null);
        return document;
    }

    /** 根据 ID 查询文档。 */
    @Override
    public Optional<KbDocument> findDocumentById(UUID docId) {
        return kbDocumentJpaRepository.findById(docId).map(this::toDomain);
    }

    /** 查询知识库下的所有文档。 */
    @Override
    public List<KbDocument> findDocumentsByKb(String kbName, UUID accountId) {
        return kbDocumentJpaRepository.findByKbNameAndAccountId(kbName, accountId).stream()
                .map(this::toDomain).toList();
    }

    /** 分页查询知识库文档。 */
    @Override
    public List<KbDocument> findDocumentsByKbPage(String kbName, UUID accountId, int page, int size) {
        return kbDocumentJpaRepository.findByKbNameAndAccountId(kbName, accountId, PageRequest.of(page, size))
                .stream().map(this::toDomain).toList();
    }

    /** 根据 ID 删除文档。 */
    @Override
    public void deleteDocument(UUID docId) {
        kbDocumentJpaRepository.deleteById(docId);
    }

    /** 统计知识库中的文档数量。 */
    @Override
    public int countDocumentsByKb(String kbName, UUID accountId) {
        return kbDocumentJpaRepository.countByKbNameAndAccountId(kbName, accountId);
    }

    /** 统计知识库中的总字符数。 */
    @Override
    public int sumCharCountByKb(String kbName, UUID accountId) {
        return kbDocumentJpaRepository.sumCharCountByKbNameAndAccountId(kbName, accountId);
    }

    /** 存储文本块及其向量。 */
    @Override
    public void storeChunks(String kbName, UUID accountId, List<Chunk> chunks, List<float[]> vectors) {
        if (chunks == null || vectors == null || chunks.size() != vectors.size()) {
            return;
        }
        List<KbVectorEntity> entities = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            Chunk chunk = chunks.get(i);
            float[] vector = vectors.get(i);
            KbVectorEntity entity = KbVectorEntity.builder()
                    .kbName(kbName)
                    .accountId(accountId)
                    .chunkId(UUID.fromString(chunk.getChunkId()))
                    .docId(chunk.getDocId())
                    .textContent(chunk.getText())
                    .contentType(chunk.getContentType() != null ? chunk.getContentType() : "text")
                    .imagePath(chunk.getImagePath())
                    .vector(vector)
                    .contentHash(chunk.getContentHash())
                    .metadata(toJsonString(chunk.getMetadata()))
                    .isDeleted(false)
                    .build();
            entities.add(entity);
        }
        kbVectorJpaRepository.saveAll(entities);
    }

    /** 向量搜索知识库。 */
    @Override
    public List<SearchResult> search(String kbName, UUID accountId, float[] queryVector, int topK) {
        String pgVector = floatArrayToPgVector(queryVector);
        List<Object[]> rows = kbVectorJpaRepository.vectorSearch(kbName, accountId, pgVector, topK);
        return rows.stream().map(this::parseSearchResult).toList();
    }

    /** 向量搜索 + 重排序。多取 topK*3 候选经 Reranker 重排后返回 topK。 */
    @Override
    public List<SearchResult> searchWithRerank(String kbName, UUID accountId, float[] queryVector,
                                                int topK, String queryText) {
        int fetchTopK = Math.min(topK * 3, 100);
        List<SearchResult> results = search(kbName, accountId, queryVector, fetchTopK);
        if (results.isEmpty()) return results;

        try {
            List<String> texts = results.stream().map(SearchResult::getText).toList();
            List<Integer> rerankedIndices = rerankerService.rerank(queryText, texts);
            if (rerankedIndices != null && !rerankedIndices.isEmpty()) {
                return rerankedIndices.stream()
                        .filter(i -> i < results.size())
                        .limit(topK)
                        .map(results::get)
                        .toList();
            }
        } catch (Exception e) {
            log.warn("Reranker 重排序失败，按原始得分排序: {}", e.getMessage());
        }
        return results.stream()
                .sorted((a, b) -> Double.compare(b.getScore(), a.getScore()))
                .limit(topK)
                .toList();
    }

    /** 跨知识库全局向量搜索。 */
    @Override
    public List<SearchResult> searchAll(UUID accountId, float[] queryVector, int topK) {
        String pgVector = floatArrayToPgVector(queryVector);
        List<Object[]> rows = kbVectorJpaRepository.vectorSearchAll(accountId, pgVector, topK);
        return rows.stream().map(this::parseSearchResult).toList();
    }

    /** 全文搜索知识库。 */
    @Override
    public List<SearchResult> fulltextSearch(UUID accountId, String query, int topK) {
        List<Object[]> rows = kbVectorJpaRepository.fulltextSearch(accountId, query, topK);
        return rows.stream().map(this::parseSearchResult).toList();
    }

    /** 软删除文档的所有文本块。 */
    @Override
    public List<Chunk> findChunksByDocId(UUID docId) {
        return kbVectorJpaRepository.findByDocIdAndIsDeletedFalse(docId)
                .stream()
                .map(e -> {
                    var meta = e.getMetadata() != null ? e.getMetadata() : "{}";
                    java.util.Map<String, Object> metadata;
                    try {
                        metadata = OBJECT_MAPPER.readValue(meta, new TypeReference<java.util.Map<String, Object>>() {});
                    } catch (Exception ex) {
                        metadata = java.util.Map.of();
                    }
                    return Chunk.builder()
                            .chunkId(e.getChunkId().toString())
                            .kbName(e.getKbName())
                            .docId(e.getDocId())
                            .text(e.getTextContent())
                            .contentType(e.getContentType())
                            .contentHash(null) // V69 迁移后可通过 e.getContentHash() 获取
                            .metadata(metadata)
                            .build();
                })
                .toList();
    }

    @Override
    public void deleteDocumentChunks(String kbName, UUID docId) {
        List<KbVectorEntity> chunks = kbVectorJpaRepository.findByDocIdAndIsDeletedFalse(docId);
        for (KbVectorEntity chunk : chunks) {
            chunk.setIsDeleted(true);
        }
        kbVectorJpaRepository.saveAll(chunks);
    }

    /** 统计知识库中的文本块数量。 */
    @Override
    public int countChunks(String kbName, UUID accountId) {
        return kbVectorJpaRepository.countByKbNameAndAccountIdAndIsDeletedFalse(kbName, accountId);
    }

    /** 重建知识库索引：软删除全部现有分块。 */
    @Override
    public void rebuildIndex(String kbName, UUID accountId, Consumer<Float> progressCallback) {
        int deleted = kbVectorJpaRepository.softDeleteAllByKb(kbName, accountId);
        log.info("知识库分块已清空: kbName={}, deleted={}", kbName, deleted);
    }

    /** 批量移动文档到目标知识库。 */
    @Override
    @Transactional
    public void moveDocuments(UUID accountId, List<UUID> docIds, String targetKbName) {
        kbDocumentJpaRepository.batchMoveDocuments(docIds, targetKbName, accountId);
        kbVectorJpaRepository.batchMoveVectors(docIds, targetKbName, accountId);
    }

    // ---- 转换方法 ----

    /** 将实体转换为知识库领域对象。 */
    private KnowledgeBase toDomain(KnowledgeBaseEntity entity) {
        return KnowledgeBase.builder()
                .kbName(entity.getKbName())
                .accountId(entity.getAccountId())
                .projectId(entity.getProjectId())
                .description(entity.getDescription())
                .createdAt(entity.getCreatedAt() != null ? entity.getCreatedAt().toLocalDateTime() : null)
                .updatedAt(entity.getUpdatedAt() != null ? entity.getUpdatedAt().toLocalDateTime() : null)
                .build();
    }

    /** 将知识库领域对象转换为实体。 */
    private KnowledgeBaseEntity toEntity(KnowledgeBase kb) {
        KnowledgeBaseEntity entity = new KnowledgeBaseEntity();
        entity.setKbName(kb.getKbName());
        entity.setAccountId(kb.getAccountId());
        entity.setProjectId(kb.getProjectId());
        entity.setDescription(kb.getDescription());
        return entity;
    }

    /** 将实体转换为文档领域对象。 */
    private KbDocument toDomain(KbDocumentEntity entity) {
        return KbDocument.builder()
                .docId(entity.getDocId())
                .kbName(entity.getKbName())
                .accountId(entity.getAccountId())
                .filename(entity.getFilename())
                .sourcePath(entity.getSourcePath())
                .fileType(entity.getFileType())
                .charCount(entity.getCharCount())
                .chunkCount(entity.getChunkCount())
                .textContent(entity.getTextContent())
                .createdAt(entity.getCreatedAt() != null ? entity.getCreatedAt().toLocalDateTime() : null)
                .build();
    }

    /** 将文档领域对象转换为实体。 */
    private KbDocumentEntity toEntity(KbDocument document) {
        KbDocumentEntity entity = new KbDocumentEntity();
        entity.setDocId(document.getDocId());
        entity.setKbName(document.getKbName());
        entity.setAccountId(document.getAccountId());
        entity.setFilename(document.getFilename());
        entity.setSourcePath(document.getSourcePath());
        entity.setFileType(document.getFileType());
        entity.setCharCount(document.getCharCount() != null ? document.getCharCount() : 0);
        entity.setChunkCount(document.getChunkCount() != null ? document.getChunkCount() : 0);
        entity.setTextContent(document.getTextContent());
        return entity;
    }

    /** 将数据库查询结果行解析为 SearchResult。 */
    private SearchResult parseSearchResult(Object[] row) {
        String metadataJson = row[8] != null ? (String) row[8] : "{}";
        return SearchResult.builder()
                .chunkId(row[3] != null ? row[3].toString() : null)
                .kbName(row[1] != null ? (String) row[1] : null)
                .text(row[5] != null ? (String) row[5] : null)
                .contentType(row[6] != null ? (String) row[6] : null)
                .imagePath(row[7] != null ? (String) row[7] : null)
                .score(row[9] != null ? ((Number) row[9]).doubleValue() : 0.0)
                .metadata(parseJsonMap(metadataJson))
                .build();
    }

    /** 将 float 数组转为 PostgreSQL 数组字面量字符串（用于 CAST 为 vector）。 */
    private String floatArrayToPgVector(float[] vector) {
        if (vector == null) {
            return "[]";
        }
        return Arrays.toString(vector);
    }

    /** 将对象序列化为 JSON 字符串。 */
    private String toJsonString(Object obj) {
        if (obj == null) {
            return "{}";
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(obj);
        } catch (Exception e) { log.warn("JSON 序列化/反序列化失败", e);
            return "{}";
        }
    }

    /** 将 JSON 字符串解析为 Map。 */
    private java.util.Map<String, Object> parseJsonMap(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            return OBJECT_MAPPER.readValue(json, new TypeReference<java.util.Map<String, Object>>() {});
        } catch (Exception e) { log.warn("JSON 序列化/反序列化失败", e);
            return Collections.emptyMap();
        }
    }
}
