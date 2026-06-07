package com.icusu.sivan.domain.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;

/**
 * LLM 提供商配置实体。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmProvider {

    private UUID providerId;
    private UUID accountId;
    private String name;
    private String providerType;
    @JsonIgnore
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String apiKey;
    private String baseUrl;
    private String models;
    private Boolean active;
    private String capabilities;
    private Integer contextLength;
    private Double temperature;
    private String tags;
    /** 是否为 Chat 默认提供商。各 tag 组独立，DB 层有唯一索引保证。 */
    @Builder.Default
    private Boolean isChatDefault = false;

    /** 是否为 Embedding 默认提供商。 */
    @Builder.Default
    private Boolean isEmbedDefault = false;

    /** 是否为 Reranker 默认提供商。 */
    @Builder.Default
    private Boolean isRerankDefault = false;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /** 获取主模型名称（逗号分隔的第一个） */
    public String getPrimaryModelName() {
        if (models == null || models.isBlank()) return "";
        return models.split(",")[0].trim();
    }

    /** 判断是否支持指定能力（通过 ModelCapability 枚举精确匹配） */
    public boolean supportsCapability(String capability) {
        if (capabilities == null || capabilities.isBlank()) return false;
        return ModelCapability.parseCapabilities(capabilities).stream()
                .anyMatch(c -> c.getCode().equals(capability));
    }

    /** 是否支持图片理解 */
    public boolean supportsVision() {
        return supportsCapability("vision");
    }

    /** 获取已解析的能力集合 */
    public Set<ModelCapability> getCapabilitySet() {
        return ModelCapability.parseCapabilities(capabilities);
    }

    /** 兼容：按 tag 判断是否为此组的默认提供商。 */
    public Boolean getIsDefault() {
        if (tags == null) return false;
        if (tags.contains("chat")) return isChatDefault;
        if (tags.contains("embedding")) return isEmbedDefault;
        if (tags.contains("reranker")) return isRerankDefault;
        return false;
    }

    /** 按 tag 组设为默认。 */
    public void setAsDefault() {
        if (tags == null) return;
        if (tags.contains("chat")) isChatDefault = true;
        if (tags.contains("embedding")) isEmbedDefault = true;
        if (tags.contains("reranker")) isRerankDefault = true;
    }

    /** 按 tag 组取消默认。 */
    public void unsetDefault() {
        if (tags == null) return;
        if (tags.contains("chat")) isChatDefault = false;
        if (tags.contains("embedding")) isEmbedDefault = false;
        if (tags.contains("reranker")) isRerankDefault = false;
    }

    public boolean isActive() { return Boolean.TRUE.equals(this.active); }
    public void updateFrom(String name, String providerType, String apiKey, String baseUrl, String models, String capabilities, Boolean active, Double temperature, Integer contextLength) {
        if (name != null) this.name = name;
        if (providerType != null) this.providerType = providerType;
        if (apiKey != null) this.apiKey = apiKey;
        if (baseUrl != null) this.baseUrl = baseUrl;
        if (models != null) this.models = models;
        if (capabilities != null) this.capabilities = capabilities;
        if (active != null) this.active = active;
        if (temperature != null) this.temperature = temperature;
        if (contextLength != null) this.contextLength = contextLength;
        this.updatedAt = LocalDateTime.now();
    }
}
