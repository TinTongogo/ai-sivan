package com.icusu.sivan.application.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 模型 Token 消耗汇总。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModelTokenSummary {
    private String modelName;
    private long totalInput;
    private long totalOutput;
    private long totalTokens;
}
