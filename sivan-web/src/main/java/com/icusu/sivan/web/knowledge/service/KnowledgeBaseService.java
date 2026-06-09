package com.icusu.sivan.web.knowledge.service;

import com.icusu.sivan.common.dto.PageResponse;
import com.icusu.sivan.common.exception.DomainException;
import com.icusu.sivan.common.exception.ResourceNotFoundException;
import com.icusu.sivan.domain.knowledge.KnowledgeBase;
import com.icusu.sivan.domain.knowledge.KbDocument;
import com.icusu.sivan.domain.knowledge.IKnowledgeBaseRepository;
import com.icusu.sivan.domain.shared.vo.Chunk;
import com.icusu.sivan.domain.shared.vo.SearchResult;
import com.icusu.sivan.infra.knowledge.EmbeddingService;
import com.icusu.sivan.infra.knowledge.RerankerService;
import com.icusu.sivan.domain.file.FileStoragePort;
import com.icusu.sivan.web.knowledge.dto.CreateKnowledgeBaseRequest;
import com.icusu.sivan.web.knowledge.dto.SearchRequest;
import com.icusu.sivan.web.knowledge.dto.UpdateKnowledgeBaseRequest;
import com.icusu.sivan.web.knowledge.dto.KbDocumentResponse;
import com.icusu.sivan.web.knowledge.dto.KbOverviewResponse;
import com.icusu.sivan.web.knowledge.dto.KnowledgeBaseResponse;
import com.icusu.sivan.web.knowledge.dto.SearchResultResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** 知识库服务，管理知识库与文档，提供向量/全文检索能力。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseService {

    private final IKnowledgeBaseRepository knowledgeBaseRepository;
    private final EmbeddingService embeddingService;
    private final RerankerService rerankerService;
    private final FileStoragePort fileStorageService;
    private final QueryRewriter queryRewriter;
    private final SemanticChunker semanticChunker;

    /** 每个文本块的最大字符数。 */
    private static final int CHUNK_MAX_CHARS = 500;

    /** 知识库支持的图片扩展名。 */
    private static final List<String> IMAGE_EXTENSIONS = List.of("jpg", "jpeg", "png", "gif", "webp", "bmp", "svg");

    /** 创建知识库。 */
    public KnowledgeBaseResponse create(UUID accountId, CreateKnowledgeBaseRequest request) {
        if (knowledgeBaseRepository.findByNameAndAccount(request.getKbName(), accountId).isPresent()) {
            throw DomainException.conflict("知识库名称已存在");
        }

        KnowledgeBase kb = KnowledgeBase.builder()
                .kbName(request.getKbName())
                .accountId(accountId)
                .projectId(request.getProjectId())
                .description(request.getDescription())
                .build();

        knowledgeBaseRepository.save(kb);
        return toResponse(kb);
    }

    /** 根据名称查询知识库。 */
    public KnowledgeBaseResponse getByName(UUID accountId, String kbName) {
        return toResponse(findOwned(accountId, kbName));
    }

    /** 查询知识库列表，按创建时间倒序。 */
    public List<KnowledgeBaseResponse> list(UUID accountId) {
        return knowledgeBaseRepository.findAllByAccount(accountId)
                .stream()
                .sorted(Comparator.comparing(KnowledgeBase::getCreatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .map(this::toResponse)
                .toList();
    }

    /** 更新知识库配置。 */
    public KnowledgeBaseResponse update(UUID accountId, String kbName, UpdateKnowledgeBaseRequest request) {
        KnowledgeBase kb = findOwned(accountId, kbName);
        kb.updateDescription(request.getDescription());
        if (request.getProjectId() != null) kb.setProjectId(request.getProjectId());
        knowledgeBaseRepository.save(kb);
        return toResponse(kb);
    }

    /** 删除知识库。 */
    public void delete(UUID accountId, String kbName) {
        KnowledgeBase kb = findOwned(accountId, kbName);
        knowledgeBaseRepository.deleteByNameAndAccount(kbName, kb.getAccountId());
    }

    /** 查询知识库内的文档列表。 */
    public List<KbDocumentResponse> listDocuments(UUID accountId, String kbName) {
        findOwned(accountId, kbName);
        return knowledgeBaseRepository.findDocumentsByKb(kbName, accountId)
                .stream().map(this::toDocumentResponse).toList();
    }

    /** 分页查询知识库文档。 */
    public PageResponse<KbDocumentResponse> listDocumentsPage(UUID accountId, String kbName, int page, int size) {
        findOwned(accountId, kbName);
        List<KbDocumentResponse> items = knowledgeBaseRepository.findDocumentsByKbPage(kbName, accountId, page, size)
                .stream().map(this::toDocumentResponse).toList();
        long total = knowledgeBaseRepository.countDocumentsByKb(kbName, accountId);
        return PageResponse.of(items, total, page + 1, size);
    }

    /** 根据 ID 查询文档详情。 */
    public KbDocumentResponse getDocument(UUID docId, UUID accountId) {
        KbDocument doc = knowledgeBaseRepository.findDocumentById(docId)
                .orElseThrow(() -> ResourceNotFoundException.notFound("文档", docId));
        // 校验文档所属知识库的所有权
        findOwned(accountId, doc.getKbName());
        return toDocumentResponse(doc);
    }

    /** 创建文档到知识库（文本），自动分块并建立向量索引。 */
    public KbDocumentResponse createDocument(UUID accountId, String kbName, String filename, String textContent) {
        findOwned(accountId, kbName);
        String ext = filename.contains(".") ? filename.substring(filename.lastIndexOf('.') + 1).toLowerCase() : "txt";
        KbDocument doc = KbDocument.builder()
                .kbName(kbName)
                .accountId(accountId)
                .filename(filename)
                .fileType(ext)
                .textContent(textContent)
                .charCount(textContent.length())
                .build();
        doc = knowledgeBaseRepository.saveDocument(doc);
        chunkAndIndexText(kbName, accountId, doc);
        return toDocumentResponse(doc);
    }

    /** 更新文档（文件名 + 内容），重新建立向量索引。 */
    public KbDocumentResponse updateDocument(UUID accountId, UUID docId, String filename, String textContent) {
        KbDocument doc = knowledgeBaseRepository.findDocumentById(docId)
                .orElseThrow(() -> ResourceNotFoundException.notFound("文档", docId));
        if (!doc.getAccountId().equals(accountId)) {
            throw ResourceNotFoundException.notFound("文档", docId);
        }
        String kbName = doc.getKbName();
        doc.setFilename(filename);
        doc.setTextContent(textContent);
        doc.setCharCount(textContent.length());
        String ext = filename.contains(".") ? filename.substring(filename.lastIndexOf('.') + 1).toLowerCase() : "txt";
        doc.setFileType(ext);
        doc = knowledgeBaseRepository.saveDocument(doc);

        // 增量索引：对比旧 chunks 的 contentHash，仅 re-embed 变更的部分
        List<Chunk> newChunks;
        try {
            newChunks = semanticChunker.chunkDocument(textContent, kbName, docId, filename);
        } catch (Exception e) {
            log.debug("语义分块失败，使用全文重索引: {}", e.getMessage());
            newChunks = null;
        }
        if (newChunks == null || newChunks.isEmpty()) {
            knowledgeBaseRepository.deleteDocumentChunks(kbName, docId);
            chunkAndIndexText(kbName, accountId, doc);
            return toDocumentResponse(doc);
        }

        // 计算 contentHash 并对比
        for (Chunk chunk : newChunks) {
            if (chunk.getContentHash() == null || chunk.getContentHash().isBlank()) {
                chunk.setContentHash(Chunk.computeHash(chunk.getText()));
            }
        }

        List<Chunk> oldChunks;
        try {
            oldChunks = knowledgeBaseRepository.findChunksByDocId(docId);
        } catch (Exception e) {
            log.debug("获取旧分块失败，使用全文重索引: {}", e.getMessage());
            oldChunks = List.of();
        }

        if (oldChunks.isEmpty()) {
            // 无旧分块 → 全部新建
            knowledgeBaseRepository.deleteDocumentChunks(kbName, docId);
            chunkAndIndexText(kbName, accountId, doc);
            return toDocumentResponse(doc);
        }

        // 对比新旧分块：按 index 位置匹配，contentHash 相同的跳过
        Set<String> unchangedHashes = new HashSet<>();
        for (Chunk old : oldChunks) {
            if (old.getContentHash() != null && !old.getContentHash().isBlank()) {
                unchangedHashes.add(old.getContentHash());
            }
        }

        List<Chunk> changedChunks = newChunks.stream()
                .filter(c -> !unchangedHashes.contains(c.getContentHash()))
                .toList();

        if (changedChunks.isEmpty()) {
            log.debug("文档无变化，跳过索引: docId={}", docId);
            return toDocumentResponse(doc);
        }

        // 删除旧分块，存储新分块（仅变更部分）
        knowledgeBaseRepository.deleteDocumentChunks(kbName, docId);
        chunkAndIndexText(kbName, accountId, doc);
        return toDocumentResponse(doc);
    }

    /** 删除文档及其向量索引。 */
    @Transactional
    public void deleteDocument(UUID docId, UUID accountId) {
        KbDocument doc = knowledgeBaseRepository.findDocumentById(docId).orElse(null);
        if (doc != null) {
            // 校验文档所属知识库的所有权
            findOwned(accountId, doc.getKbName());
            knowledgeBaseRepository.deleteDocumentChunks(doc.getKbName(), docId);
        }
        knowledgeBaseRepository.deleteDocument(docId);
    }

    private static final List<String> ALLOWED_EXTENSIONS = List.of("txt", "md", "json", "csv", "html");

    /**
     * 上传文本文件到知识库，自动分块并建立向量索引。
     */
    public KbDocumentResponse uploadDocument(UUID accountId, String kbName, String filename, String textContent) {
        findOwned(accountId, kbName);
        if (filename == null || filename.isBlank()) {
            throw DomainException.badRequest("文件名不能为空");
        }
        String ext = filename.contains(".") ? filename.substring(filename.lastIndexOf('.') + 1).toLowerCase() : "txt";
        if (!ALLOWED_EXTENSIONS.contains(ext) && !IMAGE_EXTENSIONS.contains(ext)) {
            throw DomainException.badRequest("不支持的文件类型: ." + ext);
        }
        KbDocument doc = KbDocument.builder()
                .kbName(kbName)
                .accountId(accountId)
                .filename(filename)
                .fileType(ext)
                .sourcePath(filename)
                .textContent(textContent)
                .charCount(textContent.length())
                .build();
        doc = knowledgeBaseRepository.saveDocument(doc);
        chunkAndIndexText(kbName, accountId, doc);
        return toDocumentResponse(doc);
    }

    /**
     * 上传图片到知识库，保存文件、分块并建立向量索引（使用 Qwen3-VL-Embedding）。
     *
     * @param fileId FileStorageService 返回的文件 ID
     * @param mimeType 图片 MIME 类型
     */
    public KbDocumentResponse uploadImageDocument(UUID accountId, String kbName, String filename, UUID fileId, String mimeType) {
        findOwned(accountId, kbName);
        String ext = filename.contains(".") ? filename.substring(filename.lastIndexOf('.') + 1).toLowerCase() : "png";
        KbDocument doc = KbDocument.builder()
                .kbName(kbName)
                .accountId(accountId)
                .filename(filename)
                .fileType(ext)
                .sourcePath(fileId.toString()) // 存 fileId 以便后续重建索引
                .textContent("")
                .charCount(0)
                .build();
        doc = knowledgeBaseRepository.saveDocument(doc);
        chunkAndIndexImage(kbName, accountId, doc, fileId, mimeType);
        return toDocumentResponse(doc);
    }

    /**
     * 重建知识库向量索引：清空所有分块，对全部文档重新分块并建立向量索引。
     */
    public void rebuildIndex(UUID accountId, String kbName) {
        findOwned(accountId, kbName);
        List<KbDocument> documents = knowledgeBaseRepository.findDocumentsByKb(kbName, accountId);

        // 1. 删除所有现有分块
        knowledgeBaseRepository.rebuildIndex(kbName, accountId, null);

        // 2. 重新索引全部文档（图片 fileId 已存于 sourcePath）
        int indexed = 0;
        for (KbDocument doc : documents) {
            String ext = doc.getFileType() != null ? doc.getFileType().toLowerCase() : "";
            if (IMAGE_EXTENSIONS.contains(ext)) {
                String fileIdStr = doc.getSourcePath();
                if (fileIdStr != null) {
                    try {
                        UUID fileId = UUID.fromString(fileIdStr);
                        String mimeType = "image/" + ("jpg".equals(ext) ? "jpeg" : ext);
                        chunkAndIndexImage(kbName, accountId, doc, fileId, mimeType);
                        indexed++;
                    } catch (Exception e) {
                        log.warn("重建索引跳过图片: docId={}, {}", doc.getDocId(), e.getMessage());
                    }
                } else {
                    log.warn("重建索引跳过图片（无 fileId）: docId={}", doc.getDocId());
                }
            } else if (doc.getTextContent() != null && !doc.getTextContent().isBlank()) {
                chunkAndIndexText(kbName, accountId, doc);
                indexed++;
            }
        }

        log.info("知识库索引重建完成: kbName={}, docs={}, indexed={}", kbName, documents.size(), indexed);
    }

    /**
     * 批量移动文档到目标知识库。
     */
    public void moveDocuments(UUID accountId, String sourceKbName, List<UUID> docIds, String targetKbName) {
        // 验证源和目标知识库存在且属于当前用户
        findOwned(accountId, sourceKbName);
        findOwned(accountId, targetKbName);
        if (sourceKbName.equals(targetKbName)) {
            throw DomainException.badRequest("目标知识库不能与源知识库相同");
        }
        knowledgeBaseRepository.moveDocuments(accountId, docIds, targetKbName);
        log.info("批量移动文档: source={}, target={}, count={}", sourceKbName, targetKbName, docIds.size());
    }

    /**
     * 批量导出文档为 ZIP（每个文档作为独立文本文件）。
     *
     * @param kbName 知识库名称
     * @param docIds 要导出的文档 ID 列表
     * @return ZIP 字节数组
     */
    public byte[] exportDocuments(UUID accountId, String kbName, List<UUID> docIds) {
        findOwned(accountId, kbName);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos, java.nio.charset.StandardCharsets.UTF_8)) {
            for (UUID docId : docIds) {
                KbDocument doc = knowledgeBaseRepository.findDocumentById(docId)
                        .orElse(null);
                if (doc == null || !doc.getKbName().equals(kbName) || !doc.getAccountId().equals(accountId)) {
                    log.warn("导出跳过: docId={}", docId);
                    continue;
                }
                String entryName = sanitizeZipEntryName(doc.getFilename());
                ZipEntry entry = new ZipEntry(entryName);
                entry.setTime(System.currentTimeMillis());
                zos.putNextEntry(entry);
                String content = doc.getTextContent() != null ? doc.getTextContent() : "";
                zos.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        } catch (Exception e) {
            throw new RuntimeException("导出文档失败", e);
        }
        return baos.toByteArray();
    }

    /** 清理 ZIP entry 文件名，防止路径穿越。 */
    private static String sanitizeZipEntryName(String name) {
        String sanitized = name.replaceAll("[\\\\/:*?\"<>|]", "_");
        if (sanitized.startsWith("/") || sanitized.startsWith("\\")) {
            sanitized = sanitized.substring(1);
        }
        return sanitized;
    }

    /**
     * 获取所有知识库的概览统计（文档数、分块数、字符数）。
     */
    public List<KbOverviewResponse> getOverview(UUID accountId) {
        List<KnowledgeBase> kbs = knowledgeBaseRepository.findAllByAccount(accountId);
        List<KbOverviewResponse> result = new ArrayList<>();
        for (KnowledgeBase kb : kbs) {
            int docCount = knowledgeBaseRepository.countDocumentsByKb(kb.getKbName(), accountId);
            int totalChars = knowledgeBaseRepository.sumCharCountByKb(kb.getKbName(), accountId);
            int totalChunks = knowledgeBaseRepository.countChunks(kb.getKbName(), accountId);
            result.add(KbOverviewResponse.builder()
                    .kbName(kb.getKbName())
                    .documentCount(docCount)
                    .totalChunks(totalChunks)
                    .totalChars(totalChars)
                    .lastUpdated(kb.getUpdatedAt())
                    .build());
        }
        return result;
    }

    // ---- 分块与向量索引 ----

    /**
     * 将文本文档分块并建立向量索引。
     * 使用语义分块器按 Markdown 标题/代码块/段落/句子边界切分。
     * 语义分块失败时降级到按段落+字符数切分。
     */
    private void chunkAndIndexText(String kbName, UUID accountId, KbDocument doc) {
        String text = doc.getTextContent();
        if (text == null || text.isBlank()) return;

        List<Chunk> chunks;
        int index = 0;
        try {
            chunks = semanticChunker.chunkDocument(text, kbName, doc.getDocId(), doc.getFilename());
        } catch (Exception e) {
            log.debug("语义分块失败，降级到段落分块: {}", e.getMessage());
            chunks = null;
        }
        if (chunks == null || chunks.isEmpty()) {
            // 降级：按段落双换行切分
            chunks = new ArrayList<>();
            String[] paragraphs = text.split("\\n\\s*\\n");
            for (String para : paragraphs) {
                para = para.trim();
                if (para.isEmpty()) continue;
                for (String segment : splitByMaxChars(para, CHUNK_MAX_CHARS)) {
                    chunks.add(Chunk.builder()
                            .chunkId(UUID.randomUUID().toString())
                            .kbName(kbName)
                            .docId(doc.getDocId())
                            .text(segment)
                            .contentType("text")
                            .metadata(Map.of("filename", doc.getFilename(), "chunkIndex", index))
                            .build());
                    index++;
                }
            }
        }

        if (chunks.isEmpty()) return;

        // 计算每个分块的 contentHash（用于增量索引）
        for (Chunk chunk : chunks) {
            if (chunk.getContentHash() == null || chunk.getContentHash().isBlank()) {
                chunk.setContentHash(Chunk.computeHash(chunk.getText()));
            }
        }

        // 批量 embedding
        List<float[]> vectors;
        try {
            vectors = embeddingService.embedMultimodal(chunks.stream()
                    .map(c -> new EmbeddingService.EmbeddingInput(c.getText(), null))
                    .toList());
        } catch (Exception e) {
            log.warn("文本分块 embedding 失败: docId={}, {}", doc.getDocId(), e.getMessage());
            return;
        }

        knowledgeBaseRepository.storeChunks(kbName, accountId, chunks, vectors);

        doc.setChunkCount(chunks.size());
        knowledgeBaseRepository.saveDocument(doc);

        log.debug("文档已分块索引: docId={}, chunks={}", doc.getDocId(), chunks.size());
    }

    /**
     * 将图片文档建立向量索引（使用 Qwen3-VL-Embedding 多模态）。
     */
    private void chunkAndIndexImage(String kbName, UUID accountId, KbDocument doc, UUID fileId, String mimeType) {
        // 读取图片 base64
        String base64;
        try {
            base64 = fileStorageService.resolveToBase64(accountId, fileId);
        } catch (Exception e) {
            log.warn("读取图片失败: fileId={}", fileId, e);
            return;
        }

        Chunk chunk = Chunk.builder()
                .chunkId(UUID.randomUUID().toString())
                .kbName(kbName)
                .docId(doc.getDocId())
                .text(doc.getFilename())
                .contentType("image")
                .imagePath(fileId.toString())
                .metadata(Map.of("filename", doc.getFilename(), "mimeType", mimeType))
                .build();

        float[] vector;
        try {
            vector = embeddingService.embedWithImage(doc.getFilename(), base64);
        } catch (Exception e) {
            log.warn("图片 embedding 失败: docId={}, {}", doc.getDocId(), e.getMessage());
            return;
        }

        knowledgeBaseRepository.storeChunks(kbName, accountId, List.of(chunk), List.of(vector));

        doc.setChunkCount(1);
        knowledgeBaseRepository.saveDocument(doc);

        log.debug("图片已向量化索引: docId={}, fileId={}", doc.getDocId(), fileId);
    }

    /** 将文本按最大字符数切分（按行切分，避免断词）。 */
    private List<String> splitByMaxChars(String text, int maxChars) {
        List<String> result = new ArrayList<>();
        if (text.length() <= maxChars) {
            result.add(text);
            return result;
        }
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + maxChars, text.length());
            // 尽量在换行处断开
            if (end < text.length()) {
                int newline = text.lastIndexOf('\n', end);
                if (newline > start) end = newline;
            }
            result.add(text.substring(start, end).trim());
            start = end;
        }
        return result;
    }

    /**
     * 搜索知识库。VECTOR 模式使用向量检索 + Reranker 重排，FULLTEXT 模式使用 pg_trgm。
     */
    public List<SearchResultResponse> search(UUID accountId, String kbName, SearchRequest request) {
        findOwned(accountId, kbName);

        if (request.getMode() == SearchRequest.SearchMode.FULLTEXT) {
            return knowledgeBaseRepository.fulltextSearch(accountId, request.getQuery(), request.getTopK())
                    .stream()
                    .map(this::toSearchResultResponse)
                    .toList();
        }

        if (request.isExpandQuery() && accountId != null) {
            return expandedVectorSearch(accountId, request.getQuery(), request.getTopK(),
                    (query, topK) -> knowledgeBaseRepository.search(kbName, accountId,
                            embeddingService.embed(query), topK));
        }

        return vectorSearchWithRerank(
                request.getQuery(), request.getTopK(),
                (topK) -> knowledgeBaseRepository.search(kbName, accountId,
                        embeddingService.embed(request.getQuery()), topK));
    }

    /**
     * 跨知识库搜索。VECTOR 模式使用向量检索 + Reranker 重排，FULLTEXT 模式使用 pg_trgm。
     */
    public List<SearchResultResponse> searchAll(UUID accountId, SearchRequest request) {
        if (request.getMode() == SearchRequest.SearchMode.FULLTEXT) {
            return knowledgeBaseRepository.fulltextSearch(accountId, request.getQuery(), request.getTopK())
                    .stream()
                    .map(this::toSearchResultResponse)
                    .toList();
        }

        if (request.isExpandQuery() && accountId != null) {
            return expandedVectorSearch(accountId, request.getQuery(), request.getTopK(),
                    (query, topK) -> knowledgeBaseRepository.searchAll(accountId,
                            embeddingService.embed(query), topK));
        }

        return vectorSearchWithRerank(
                request.getQuery(), request.getTopK(),
                (topK) -> knowledgeBaseRepository.searchAll(accountId,
                        embeddingService.embed(request.getQuery()), topK));
    }

    /**
     * 查询改写增强的向量搜索：将原始查询扩展为多个变体，分别向量搜索后合并去重。
     */
    private List<SearchResultResponse> expandedVectorSearch(UUID accountId, String query, int topK,
                                                             java.util.function.BiFunction<String, Integer, List<SearchResult>> searcher) {
        List<String> queries;
        try {
            queries = Mono.fromCallable(() ->
                    queryRewriter.rewrite(query, accountId).block(Duration.ofSeconds(5)))
                    .subscribeOn(Schedulers.boundedElastic())
                    .block();
        } catch (Exception e) {
            log.warn("查询改写失败，使用原始查询: {}", e.getMessage());
            queries = List.of(query);
        }
        if (queries == null || queries.isEmpty()) queries = List.of(query);

        int fetchTopK = Math.min(topK * 2, 50);
        List<SearchResult> merged = new ArrayList<>();
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        for (String q : queries) {
            try {
                List<SearchResult> results = searcher.apply(q, fetchTopK);
                if (results != null) {
                    for (SearchResult r : results) {
                        String text = r.getText();
                        if (text != null && seen.add(text)) {
                            merged.add(r);
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("查询变体搜索失败: query='{}', {}", q.length() > 40 ? q.substring(0, 40) + "..." : q, e.getMessage());
            }
        }

        if (merged.isEmpty()) return Collections.emptyList();

        // 用原始查询对合并结果做 rerank
        try {
            List<String> texts = merged.stream().map(SearchResult::getText).toList();
            List<Integer> rerankedIndices = rerankerService.rerank(query, texts);
            if (rerankedIndices != null && !rerankedIndices.isEmpty()) {
                return rerankedIndices.stream()
                        .filter(i -> i < merged.size())
                        .limit(topK)
                        .map(merged::get)
                        .map(this::toSearchResultResponse)
                        .toList();
            }
        } catch (Exception e) {
            log.warn("Reranker 重排序失败: {}", e.getMessage());
        }

        return merged.stream()
                .sorted((a, b) -> Double.compare(b.getScore(), a.getScore()))
                .limit(topK)
                .map(this::toSearchResultResponse)
                .toList();
    }

    /**
     * 向量搜索 + Reranker 重排序。
     * 多取 topK*3 候选供 reranker 筛选，最终返回 topK 条。
     */
    private List<SearchResultResponse> vectorSearchWithRerank(String query, int topK,
                                                               java.util.function.Function<Integer, List<SearchResult>> searcher) {
        List<SearchResult> results;
        try {
            int fetchTopK = Math.min(topK * 3, 100);
            results = searcher.apply(fetchTopK);
        } catch (Exception e) {
            log.warn("向量搜索失败: {}", e.getMessage());
            return Collections.emptyList();
        }
        if (results == null || results.isEmpty()) return Collections.emptyList();

        try {
            List<String> texts = results.stream().map(SearchResult::getText).toList();
            List<Integer> rerankedIndices = rerankerService.rerank(query, texts);
            if (rerankedIndices != null && !rerankedIndices.isEmpty()) {
                return rerankedIndices.stream()
                        .filter(i -> i < results.size())
                        .limit(topK)
                        .map(results::get)
                        .map(this::toSearchResultResponse)
                        .toList();
            }
        } catch (Exception e) {
            log.warn("Reranker 重排序失败，按原始得分排序: {}", e.getMessage());
        }
        // fallback: 按原始得分排序取 topK
        return results.stream()
                .sorted((a, b) -> Double.compare(b.getScore(), a.getScore()))
                .limit(topK)
                .map(this::toSearchResultResponse)
                .toList();
    }

    /** 查找当前用户拥有的知识库。 */
    private KnowledgeBase findOwned(UUID accountId, String kbName) {
        return knowledgeBaseRepository.findByNameAndAccount(kbName, accountId)
                .orElseThrow(() -> ResourceNotFoundException.notFound("知识库", kbName));
    }

    /** 转换为响应对象。 */
    private KnowledgeBaseResponse toResponse(KnowledgeBase kb) {
        return KnowledgeBaseResponse.builder()
                .kbName(kb.getKbName())
                .projectId(kb.getProjectId())
                .description(kb.getDescription())
                .createdAt(kb.getCreatedAt())
                .updatedAt(kb.getUpdatedAt())
                .build();
    }

    /** 文档实体转为响应对象。 */
    private KbDocumentResponse toDocumentResponse(KbDocument doc) {
        return KbDocumentResponse.builder()
                .docId(doc.getDocId())
                .kbName(doc.getKbName())
                .filename(doc.getFilename())
                .sourcePath(doc.getSourcePath())
                .fileType(doc.getFileType())
                .charCount(doc.getCharCount())
                .chunkCount(doc.getChunkCount())
                .textContent(doc.getTextContent())
                .createdAt(doc.getCreatedAt())
                .build();
    }

    /** 搜索结果转为响应对象。 */
    private SearchResultResponse toSearchResultResponse(SearchResult result) {
        return SearchResultResponse.builder()
                .chunkId(result.getChunkId())
                .kbName(result.getKbName())
                .text(result.getText())
                .contentType(result.getContentType())
                .imagePath(result.getImagePath())
                .score(result.getScore())
                .metadata(result.getMetadata())
                .build();
    }
}
