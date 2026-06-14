package com.icusu.sivan.application.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * LLM 提供商响应 DTO。
 */
@Data
@Builder
@AllArgsConstructor
public class LlmProviderResponse {

    private UUID providerId;
    private String name;
    private String providerType;
    private String apiKey;
    private String baseUrl;
    private String model;
    private Boolean active;
    private Boolean isDefault;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    private String capabilities;
    private Integer contextLength;
    private Double temperature;
    private String tags;
}
