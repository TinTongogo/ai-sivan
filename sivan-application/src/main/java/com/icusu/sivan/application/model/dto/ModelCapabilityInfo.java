package com.icusu.sivan.application.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

/**
 * 模型能力信息 DTO。
 */
@Data
@Builder
@AllArgsConstructor
public class ModelCapabilityInfo {
    private String code;
    private String label;
}
