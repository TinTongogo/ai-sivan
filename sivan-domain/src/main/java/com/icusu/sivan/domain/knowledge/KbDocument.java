package com.icusu.sivan.domain.knowledge;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 知识库文档实体（10-知识库与RAG §6.2）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KbDocument {

    private UUID docId;
    private String kbName;
    private UUID accountId;
    private String filename;
    private String sourcePath;
    private String fileType;
    private Integer charCount;
    private Integer chunkCount;
    private String textContent;
    /** 索引状态，兼容旧版字符串状态。 */
    @Builder.Default
    private DocumentIndexStatus indexStatus = DocumentIndexStatus.PENDING;
    /** 全文 contentHash（用于增量索引去重）。 */
    private String contentHash;
    /** 索引失败时的错误信息。 */
    private String errorMessage;
    private LocalDateTime createdAt;
    /** 最后索引时间。 */
    private LocalDateTime indexedAt;

    public void markReady() { this.indexStatus = DocumentIndexStatus.INDEXED; }
    public void markFailed(String reason) { this.indexStatus = DocumentIndexStatus.FAILED; this.errorMessage = reason; }
    public void markParsing() { this.indexStatus = DocumentIndexStatus.PENDING; }
    public boolean isReady() { return DocumentIndexStatus.INDEXED.equals(this.indexStatus); }
}
