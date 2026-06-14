package com.icusu.sivan.application.knowledge.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 知识库文档响应 DTO。
 */
@Data
@Builder
@AllArgsConstructor
public class KbDocumentResponse {
    private UUID docId;
    private String kbName;
    private String filename;
    private String sourcePath;
    private String fileType;
    private Integer charCount;
    private Integer chunkCount;
    private String textContent;
    private LocalDateTime createdAt;
}
