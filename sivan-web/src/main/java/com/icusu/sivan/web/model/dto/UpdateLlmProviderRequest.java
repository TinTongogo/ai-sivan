package com.icusu.sivan.web.model.dto;

import lombok.Data;

/**
 * 更新 LLM 提供商请求 DTO。
 */
@Data
public class UpdateLlmProviderRequest {

    private String name;

    private String providerType;

    private String apiKey;

    private String baseUrl;

    private String model;

    private Boolean active;

    private Boolean isDefault;

    private String capabilities;

    private Double temperature;

    private Integer contextLength;

    /** 用途标签（逗号分隔，如 chat,embedding,reranker），可选 */
    private String tags;
}
