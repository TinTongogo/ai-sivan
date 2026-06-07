package com.icusu.sivan.web.knowledge.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 知识库响应 DTO。
 */
@Data
@Builder
@AllArgsConstructor
public class KnowledgeBaseResponse {
    private String kbName;
    private UUID projectId;
    private String description;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
