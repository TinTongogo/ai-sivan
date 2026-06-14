package com.icusu.sivan.application.knowledge.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识库概览统计响应。
 */
@Data
@Builder
@AllArgsConstructor
public class KbOverviewResponse {
    private String kbName;
    private int documentCount;
    private int totalChunks;
    private int totalChars;
    private LocalDateTime lastUpdated;
}
