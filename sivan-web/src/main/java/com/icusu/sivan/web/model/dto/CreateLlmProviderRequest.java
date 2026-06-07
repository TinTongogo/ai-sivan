package com.icusu.sivan.web.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 创建 LLM 提供商请求 DTO。
 */
@Data
public class CreateLlmProviderRequest {

    @NotBlank(message = "名称为必填项")
    private String name;

    @NotBlank(message = "提供商类型为必填项")
    private String providerType;

    private String apiKey;

    private String baseUrl;

    private String model;

    private String capabilities;

    private Double temperature;

    private Integer contextLength;

    /** 用途标签（逗号分隔，如 chat,embedding,reranker），默认 chat */
    private String tags = "chat";
}
