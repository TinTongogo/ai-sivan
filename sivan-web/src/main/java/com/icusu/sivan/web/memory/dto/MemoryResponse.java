package com.icusu.sivan.web.memory.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 记忆响应 DTO。
 */
@Data
@Builder
@AllArgsConstructor
public class MemoryResponse {
    private UUID memoryId;
    private UUID projectId;
    private String projectName;
    private String level;
    private String scopeId;
    private String scopeName;
    private String content;
    private Float retention;
    private Integer accessCount;
    private Boolean archived;
    private Boolean important;
    private String summary;
    private LocalDateTime createdAt;
    private LocalDateTime lastAccessedAt;
}
