package com.icusu.sivan.web.knowledge.controller;

import com.icusu.sivan.common.dto.BaseResponse;
import com.icusu.sivan.common.dto.PageResponse;
import com.icusu.sivan.web.knowledge.dto.BatchExportRequest;
import com.icusu.sivan.web.knowledge.dto.BatchMoveDocumentsRequest;
import com.icusu.sivan.web.knowledge.dto.CreateDocumentRequest;
import com.icusu.sivan.web.knowledge.dto.CreateKnowledgeBaseRequest;
import com.icusu.sivan.web.knowledge.dto.SearchRequest;
import com.icusu.sivan.web.knowledge.dto.UpdateDocumentRequest;
import com.icusu.sivan.web.knowledge.dto.UpdateKnowledgeBaseRequest;
import com.icusu.sivan.domain.shared.vo.Chunk;
import com.icusu.sivan.web.knowledge.dto.KbDocumentResponse;
import com.icusu.sivan.web.knowledge.dto.KbOverviewResponse;
import com.icusu.sivan.web.knowledge.dto.KnowledgeBaseResponse;
import com.icusu.sivan.web.knowledge.dto.SearchResultResponse;
import com.icusu.sivan.domain.file.FileStoragePort;
import com.icusu.sivan.web.knowledge.service.KnowledgeBaseService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.*;
import com.icusu.sivan.web.shared.security.CurrentAccountId;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 知识库管理控制器。
 */
@Slf4j
@RestController
@RequestMapping("/api/v2/knowledge-bases")
@RequiredArgsConstructor
public class KnowledgeBaseController {

    /**
     * 图片扩展名集合。
     */
    private static final Set<String> IMAGE_EXTS = Set.of("jpg", "jpeg", "png", "gif", "webp", "bmp", "svg");
    private final KnowledgeBaseService knowledgeBaseService;
    private final FileStoragePort fileStorageService;

    /**
     * 创建知识库。
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BaseResponse<KnowledgeBaseResponse> create(@Valid @RequestBody CreateKnowledgeBaseRequest request, @CurrentAccountId UUID accountId) {
                return BaseResponse.created(knowledgeBaseService.create(accountId, request));
    }

    /**
     * 知识库概览统计（文档数、分块数、字符数），需放在 /{kbName} 之前避免路径冲突。
     */
    @GetMapping("/overview")
    public BaseResponse<List<KbOverviewResponse>> getOverview(@CurrentAccountId UUID accountId) {
                return BaseResponse.success(knowledgeBaseService.getOverview(accountId));
    }

    /**
     * 根据名称获取知识库。
     */
    @GetMapping("/{kbName}")
    public BaseResponse<KnowledgeBaseResponse> getByName(@PathVariable String kbName, @CurrentAccountId UUID accountId) {
                return BaseResponse.success(knowledgeBaseService.getByName(accountId, kbName));
    }

    /**
     * 获取知识库列表。
     */
    @GetMapping
    public BaseResponse<List<KnowledgeBaseResponse>> list(@CurrentAccountId UUID accountId) {
                return BaseResponse.success(knowledgeBaseService.list(accountId));
    }

    /**
     * 更新知识库信息。
     */
    @PutMapping("/{kbName}")
    public BaseResponse<KnowledgeBaseResponse> update(@PathVariable String kbName,
                                                      @Valid @RequestBody UpdateKnowledgeBaseRequest request, @CurrentAccountId UUID accountId) {
                return BaseResponse.success(knowledgeBaseService.update(accountId, kbName, request));
    }

    /**
     * 删除知识库。
     */
    @DeleteMapping("/{kbName}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public BaseResponse<Void> delete(@PathVariable String kbName, @CurrentAccountId UUID accountId) {
                knowledgeBaseService.delete(accountId, kbName);
        return BaseResponse.success();
    }

    /**
     * 获取知识库文档列表。
     */
    @GetMapping("/{kbName}/documents")
    public BaseResponse<List<KbDocumentResponse>> listDocuments(@PathVariable String kbName, @CurrentAccountId UUID accountId) {
                return BaseResponse.success(knowledgeBaseService.listDocuments(accountId, kbName));
    }

    /**
     * 分页查询知识库文档。
     */
    @GetMapping("/{kbName}/documents/page")
    public BaseResponse<PageResponse<KbDocumentResponse>> listDocumentsPage(@PathVariable String kbName,
                                                                            @RequestParam(defaultValue = "0") int page,
                                                                            @RequestParam(defaultValue = "10") int size, @CurrentAccountId UUID accountId) {
                return BaseResponse.success(knowledgeBaseService.listDocumentsPage(accountId, kbName, page, size));
    }

    /**
     * 创建知识库文档（文本方式）。
     */
    @PostMapping("/{kbName}/documents")
    @ResponseStatus(HttpStatus.CREATED)
    public BaseResponse<KbDocumentResponse> createDocument(@PathVariable String kbName,
                                                           @Valid @RequestBody CreateDocumentRequest request, @CurrentAccountId UUID accountId) {
                return BaseResponse.created(knowledgeBaseService.createDocument(accountId, kbName,
                request.getFilename(), request.getTextContent()));
    }

    /**
     * 上传文件到知识库（支持 .txt/.md/.json/.csv/.html 和图片）。
     */
    @PostMapping(value = "/{kbName}/documents/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<BaseResponse<KbDocumentResponse>> uploadDocument(@PathVariable String kbName,
                                                                 @RequestPart("file") FilePart filePart, @CurrentAccountId UUID accountId) {
        String filename = filePart.filename() != null ? filePart.filename() : "unknown";
        String ext = filename.contains(".") ? filename.substring(filename.lastIndexOf('.') + 1).toLowerCase() : "";

        // 图片走 FileStorageService 保存，再调 uploadImageDocument
        if (IMAGE_EXTS.contains(ext)) {
            return handleImageUpload(kbName, filename, filePart, accountId);
        }

        // 文本文件走原有流程
        return handleTextUpload(kbName, filename, filePart, accountId);
    }

    /**
     * 处理文本文件上传。
     */
    private Mono<BaseResponse<KbDocumentResponse>> handleTextUpload(String kbName, String filename, FilePart filePart, UUID accountId) {
                return DataBufferUtils.join(filePart.content())
                .map(db -> {
                    byte[] b = new byte[db.readableByteCount()];
                    db.read(b);
                    DataBufferUtils.release(db);
                    return new String(b, StandardCharsets.UTF_8);
                })
                .flatMap(textContent ->
                        Mono.fromCallable(() ->
                                knowledgeBaseService.uploadDocument(accountId, kbName, filename, textContent))
                        .subscribeOn(Schedulers.boundedElastic()))
                .map(BaseResponse::created);
    }

    /**
     * 处理图片文件上传。
     */
    private Mono<BaseResponse<KbDocumentResponse>> handleImageUpload(String kbName, String filename, FilePart filePart, UUID accountId) {
        return DataBufferUtils.join(filePart.content())
                .map(db -> {
                    byte[] b = new byte[db.readableByteCount()];
                    db.read(b);
                    DataBufferUtils.release(db);
                    return b;
                })
                .flatMap(bytes -> {
                    var result = fileStorageService.store(bytes, filename, null, accountId);
                    return Mono.fromCallable(() ->
                            knowledgeBaseService.uploadImageDocument(
                                    accountId, kbName, filename, result.getFileId(), result.getMimeType()))
                            .subscribeOn(Schedulers.boundedElastic());
                })
                .map(BaseResponse::created);
    }

    /**
     * 根据文档 ID 获取文档详情。
     */
    @GetMapping("/documents/{docId}")
    public BaseResponse<KbDocumentResponse> getDocument(@PathVariable UUID docId,
                                                         @CurrentAccountId UUID accountId) {
        return BaseResponse.success(knowledgeBaseService.getDocument(docId, accountId));
    }

    /**
     * 更新文档（文件名 + 内容）。
     */
    @PutMapping("/documents/{docId}")
    public BaseResponse<KbDocumentResponse> updateDocument(@PathVariable UUID docId,
                                                           @Valid @RequestBody UpdateDocumentRequest request, @CurrentAccountId UUID accountId) {
                return BaseResponse.success(knowledgeBaseService.updateDocument(accountId, docId,
                request.getFilename(), request.getTextContent()));
    }

    /**
     * 删除知识库文档。
     */
    @DeleteMapping("/documents/{docId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public BaseResponse<Void> deleteDocument(@PathVariable UUID docId,
                                              @CurrentAccountId UUID accountId) {
        knowledgeBaseService.deleteDocument(docId, accountId);
        return BaseResponse.success();
    }

    // ===== 以下为 10-知识库与RAG §6.1 新增端点 =====

    /** 重新索引单篇文档（删除旧分块，重新分块+向量化）。 */
    @PostMapping("/documents/{docId}/reindex")
    public BaseResponse<Void> reindexDocument(@PathVariable UUID docId,
                                               @CurrentAccountId UUID accountId) {
        knowledgeBaseService.reindexDocument(docId, accountId);
        return BaseResponse.success();
    }

    /** 查看文档的分块详情。 */
    @GetMapping("/documents/{docId}/chunks")
    public BaseResponse<List<Chunk>> getDocumentChunks(@PathVariable UUID docId,
                                                        @CurrentAccountId UUID accountId) {
        return BaseResponse.success(knowledgeBaseService.getDocumentChunks(docId, accountId));
    }

    /**
     * 批量移动文档到目标知识库。
     */
    @PostMapping("/{kbName}/documents/batch-move")
    public BaseResponse<Void> batchMoveDocuments(@PathVariable String kbName,
                                                 @Valid @RequestBody BatchMoveDocumentsRequest request, @CurrentAccountId UUID accountId) {
                knowledgeBaseService.moveDocuments(accountId, kbName, request.getDocIds(), request.getTargetKbName());
        return BaseResponse.success();
    }

    /**
     * 批量导出文档为 ZIP 文件下载。
     */
    @PostMapping("/{kbName}/documents/batch-export")
    public ResponseEntity<Resource> batchExportDocuments(@PathVariable String kbName,
                                                         @Valid @RequestBody BatchExportRequest request, @CurrentAccountId UUID accountId) {
                byte[] zipBytes = knowledgeBaseService.exportDocuments(accountId, kbName, request.getDocIds());
        ByteArrayResource resource = new ByteArrayResource(zipBytes);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + kbName + "-export.zip\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(zipBytes.length)
                .body(resource);
    }

    /**
     * 重建知识库向量索引。
     */
    @PostMapping("/{kbName}/rebuild-index")
    public BaseResponse<Void> rebuildIndex(@PathVariable String kbName, @CurrentAccountId UUID accountId) {
                knowledgeBaseService.rebuildIndex(accountId, kbName);
        return BaseResponse.success();
    }

    /**
     * 在知识库中搜索。
     */
    @PostMapping("/{kbName}/search")
    public BaseResponse<List<SearchResultResponse>> search(@PathVariable String kbName,
                                                           @Valid @RequestBody SearchRequest request, @CurrentAccountId UUID accountId) {
                return BaseResponse.success(knowledgeBaseService.search(accountId, kbName, request));
    }

    /**
     * 跨知识库搜索（不限定知识库）。
     */
    @PostMapping("/search")
    public BaseResponse<List<SearchResultResponse>> searchAll(@Valid @RequestBody SearchRequest request, @CurrentAccountId UUID accountId) {
                return BaseResponse.success(knowledgeBaseService.searchAll(accountId, request));
    }
}
