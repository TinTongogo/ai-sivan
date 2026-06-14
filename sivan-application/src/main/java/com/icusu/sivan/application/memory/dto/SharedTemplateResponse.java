package com.icusu.sivan.application.memory.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 共享模板响应 DTO。
 */
@Data
@Builder
@AllArgsConstructor
public class SharedTemplateResponse {
    private UUID templateId;
    private UUID patternId;
    private UUID ownerAccountId;
    private String visibility;
    private String status;
    private String quality;
    private int useCount;
    private int successCount;
    private LocalDateTime sharedAt;
    private LocalDateTime createdAt;

    // 冗余模板信息
    private String executionMode;
    private String taskDescription;
    private Integer patternVersion;
}
