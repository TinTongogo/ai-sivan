package com.icusu.sivan.domain.knowledge;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 知识库文档实体。
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
    private String status;     // PARSING / READY / FAILED
    private String errorMessage;
    private LocalDateTime createdAt;

    public static final String STATUS_PARSING = "PARSING";
    public static final String STATUS_READY = "READY";
    public static final String STATUS_FAILED = "FAILED";

    public void markReady() { this.status = STATUS_READY; }
    public void markFailed(String reason) { this.status = STATUS_FAILED; this.errorMessage = reason; }
    public void markParsing() { this.status = STATUS_PARSING; }
    public boolean isReady() { return STATUS_READY.equals(this.status); }
}
