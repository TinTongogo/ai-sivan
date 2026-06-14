package com.icusu.sivan.application.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * LLM 连接测试 / 模型拉取请求。
 */
@Data
public class LlmConnectionRequest {

    @NotBlank
    private String providerType;

    private String apiKey;

    @NotBlank
    private String baseUrl;
}
