package com.icusu.sivan.application.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * LLM 模型列表结果。
 */
@Data
@Builder
@AllArgsConstructor
public class LlmModelListResult {

    private List<String> models;
}
