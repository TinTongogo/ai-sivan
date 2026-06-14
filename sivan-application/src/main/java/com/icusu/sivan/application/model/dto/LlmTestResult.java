package com.icusu.sivan.application.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * LLM 连接测试结果。
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class LlmTestResult {

    private boolean success;
    private String message;
    private Integer contextLength;
    private List<ModelInfo> models;

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class ModelInfo {
        private String name;
        private Integer contextLength;
    }
}
