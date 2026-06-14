package com.icusu.sivan.application.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

/**
 * Embedding / Reranker 连接测试结果。
 */
@Data
@Builder
@AllArgsConstructor
public class ModelServiceTestResult {
    private boolean embeddingSuccess;
    private String embeddingMessage;
    private boolean rerankerSuccess;
    private String rerankerMessage;
}
