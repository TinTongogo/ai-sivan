package com.icusu.sivan.application.knowledge;

import com.icusu.sivan.common.dto.PageResponse;
import com.icusu.sivan.common.exception.DomainException;
import com.icusu.sivan.common.exception.ResourceNotFoundException;
import com.icusu.sivan.domain.file.FileStoragePort;
import com.icusu.sivan.domain.knowledge.IKnowledgeBaseRepository;
import com.icusu.sivan.domain.knowledge.KbDocument;
import com.icusu.sivan.domain.knowledge.KnowledgeBase;
import com.icusu.sivan.domain.shared.vo.Chunk;
import com.icusu.sivan.infra.knowledge.EmbeddingService;
import com.icusu.sivan.application.knowledge.dto.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 知识库 CRUD 服务 — 知识库和文档的增删改、上传、重建索引、导入导出。
 * <p>
 * 从 {@link KnowledgeBaseService} 拆出。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KbCrudService {

    private final IKnowledgeBaseRepository knowledgeBaseRepository;
    private final EmbeddingService embeddingService;
    private final FileStoragePort fileStorageService;
    private final SemanticChunker semanticChunker;

    private static final int CHUNK_MAX_CHARS = 500;
    private static final List<String> IMAGE_EXTENSIONS = List.of("jpg", "jpeg", "png", "gif", "webp", "bmp", "svg");
    private static final List<String> ALLOWED_EXTENSIONS = List.of("txt", "md", "json", "csv", "html");

    // ====== 知识库 CRUD ======

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

    public KnowledgeBaseResponse getByName(UUID accountId, String kbName) {
        return toResponse(findOwned(accountId, kbName));
    }

    public List<KnowledgeBaseResponse> list(UUID accountId) {
        return knowledgeBaseRepository.findAllByAccount(accountId)
                .stream()
                .sorted(Comparator.comparing(KnowledgeBase::getCreatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .map(this::toResponse)
                .toList();
    }

    public KnowledgeBaseResponse update(UUID accountId, String kbName, UpdateKnowledgeBaseRequest request) {
        KnowledgeBase kb = findOwned(accountId, kbName);
        kb.updateDescription(request.getDescription());
        if (request.getProjectId() != null) kb.setProjectId(request.getProjectId());
        knowledgeBaseRepository.save(kb);
        return toResponse(kb);
    }

    @Transactional
    public void delete(UUID accountId, String kbName) {
        KnowledgeBase kb = findOwned(accountId, kbName);
        knowledgeBaseRepository.deleteByNameAndAccount(kbName, kb.getAccountId());
    }

    // ====== 文档 CRUD ======

    public List<KbDocumentResponse> listDocuments(UUID accountId, String kbName) {
        findOwned(accountId, kbName);
        return knowledgeBaseRepository.findDocumentsByKb(kbName, accountId)
                .stream().map(this::toDocumentResponse).toList();
    }

    public PageResponse<KbDocumentResponse> listDocumentsPage(UUID accountId, String kbName, int page, int size) {
        findOwned(accountId, kbName);
        List<KbDocumentResponse> items = knowledgeBaseRepository.findDocumentsByKbPage(kbName, accountId, page, size)
                .stream().map(this::toDocumentResponse).toList();
        long total = knowledgeBaseRepository.countDocumentsByKb(kbName, accountId);
        return PageResponse.of(items, total, page + 1, size);
    }

    public KbDocumentResponse getDocument(UUID docId, UUID accountId) {
        KbDocument doc = knowledgeBaseRepository.findDocumentById(docId)
                .orElseThrow(() -> ResourceNotFoundException.notFound("文档", docId));
        findOwned(accountId, doc.getKbName());
        return toDocumentResponse(doc);
    }

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
            knowledgeBaseRepository.deleteDocumentChunks(kbName, docId);
            chunkAndIndexText(kbName, accountId, doc);
            return toDocumentResponse(doc);
        }
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
        knowledgeBaseRepository.deleteDocumentChunks(kbName, docId);
        chunkAndIndexText(kbName, accountId, doc);
        return toDocumentResponse(doc);
    }

    @Transactional
    public void deleteDocument(UUID docId, UUID accountId) {
        KbDocument doc = knowledgeBaseRepository.findDocumentById(docId).orElse(null);
        if (doc != null) {
            findOwned(accountId, doc.getKbName());
            knowledgeBaseRepository.deleteDocumentChunks(doc.getKbName(), docId);
        }
        knowledgeBaseRepository.deleteDocument(docId);
    }

    public void reindexDocument(UUID docId, UUID accountId) {
        KbDocument doc = knowledgeBaseRepository.findDocumentById(docId)
                .orElseThrow(() -> ResourceNotFoundException.notFound("文档", docId));
        findOwned(accountId, doc.getKbName());
        knowledgeBaseRepository.deleteDocumentChunks(doc.getKbName(), docId);
        chunkAndIndexText(doc.getKbName(), accountId, doc);
    }

    public List<Chunk> getDocumentChunks(UUID docId, UUID accountId) {
        KbDocument doc = knowledgeBaseRepository.findDocumentById(docId)
                .orElseThrow(() -> ResourceNotFoundException.notFound("文档", docId));
        findOwned(accountId, doc.getKbName());
        return knowledgeBaseRepository.findChunksByDocId(docId);
    }

    // ====== 上传 ======

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

    public KbDocumentResponse uploadImageDocument(UUID accountId, String kbName, String filename, UUID fileId, String mimeType) {
        findOwned(accountId, kbName);
        String ext = filename.contains(".") ? filename.substring(filename.lastIndexOf('.') + 1).toLowerCase() : "png";
        KbDocument doc = KbDocument.builder()
                .kbName(kbName)
                .accountId(accountId)
                .filename(filename)
                .fileType(ext)
                .sourcePath(fileId.toString())
                .textContent("")
                .charCount(0)
                .build();
        doc = knowledgeBaseRepository.saveDocument(doc);
        chunkAndIndexImage(kbName, accountId, doc, fileId, mimeType);
        return toDocumentResponse(doc);
    }

    // ====== 重建索引 ======

    public void rebuildIndex(UUID accountId, String kbName) {
        findOwned(accountId, kbName);
        List<KbDocument> documents = knowledgeBaseRepository.findDocumentsByKb(kbName, accountId);
        knowledgeBaseRepository.rebuildIndex(kbName, accountId, null);
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

    @Transactional
    public void rebuildAllIndexes(UUID accountId) {
        List<KnowledgeBase> allKbs = knowledgeBaseRepository.findAllByAccount(accountId);
        if (allKbs.isEmpty()) {
            log.info("无可重建的知识库（accountId={}）", accountId);
            return;
        }
        for (KnowledgeBase kb : allKbs) {
            try {
                rebuildIndex(accountId, kb.getKbName());
                log.info("知识库索引重建完成: kbName={}", kb.getKbName());
            } catch (Exception e) {
                log.error("知识库索引重建失败: kbName={}, error={}", kb.getKbName(), e.getMessage());
            }
        }
    }

    // ====== 批量操作 ======

    public void moveDocuments(UUID accountId, String sourceKbName, List<UUID> docIds, String targetKbName) {
        findOwned(accountId, sourceKbName);
        findOwned(accountId, targetKbName);
        if (sourceKbName.equals(targetKbName)) {
            throw DomainException.badRequest("目标知识库不能与源知识库相同");
        }
        knowledgeBaseRepository.moveDocuments(accountId, docIds, targetKbName);
        log.info("批量移动文档: source={}, target={}, count={}", sourceKbName, targetKbName, docIds.size());
    }

    public byte[] exportDocuments(UUID accountId, String kbName, List<UUID> docIds) {
        findOwned(accountId, kbName);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos, java.nio.charset.StandardCharsets.UTF_8)) {
            for (UUID docId : docIds) {
                KbDocument doc = knowledgeBaseRepository.findDocumentById(docId).orElse(null);
                if (doc == null || !doc.getKbName().equals(kbName) || !doc.getAccountId().equals(accountId)) {
                    log.warn("导出跳过: docId={}", docId);
                    continue;
                }
                ZipEntry entry = new ZipEntry(sanitizeZipEntryName(doc.getFilename()));
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

    // ====== 分块与向量索引 ======

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

        for (Chunk chunk : chunks) {
            if (chunk.getContentHash() == null || chunk.getContentHash().isBlank()) {
                chunk.setContentHash(Chunk.computeHash(chunk.getText()));
            }
        }

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

    private void chunkAndIndexImage(String kbName, UUID accountId, KbDocument doc, UUID fileId, String mimeType) {
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

    private List<String> splitByMaxChars(String text, int maxChars) {
        List<String> result = new ArrayList<>();
        if (text.length() <= maxChars) {
            result.add(text);
            return result;
        }
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + maxChars, text.length());
            if (end < text.length()) {
                int newline = text.lastIndexOf('\n', end);
                if (newline > start) end = newline;
            }
            result.add(text.substring(start, end).trim());
            start = end;
        }
        return result;
    }

    // ====== DTO 映射 ======

    private KnowledgeBase findOwned(UUID accountId, String kbName) {
        return knowledgeBaseRepository.findByNameAndAccount(kbName, accountId)
                .orElseThrow(() -> ResourceNotFoundException.notFound("知识库", kbName));
    }

    private KnowledgeBaseResponse toResponse(KnowledgeBase kb) {
        return KnowledgeBaseResponse.builder()
                .kbName(kb.getKbName())
                .projectId(kb.getProjectId())
                .description(kb.getDescription())
                .createdAt(kb.getCreatedAt())
                .updatedAt(kb.getUpdatedAt())
                .build();
    }

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

    private static String sanitizeZipEntryName(String name) {
        String sanitized = name.replaceAll("[\\\\/:*?\"<>|]", "_");
        if (sanitized.startsWith("/") || sanitized.startsWith("\\")) {
            sanitized = sanitized.substring(1);
        }
        return sanitized;
    }
}
