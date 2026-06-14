package com.icusu.sivan.infra.model.entity;

import com.icusu.sivan.infra.shared.entity.BaseEntity;

import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

/**
 * llm_providers 表 JPA 实体，表示 LLM 服务提供商配置。
 */
@Entity
@Table(name = "llm_providers")
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LlmProviderEntity extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID providerId;

    @Column(name = "account_id")
    private UUID accountId;

    @Column(length = 256)
    @Builder.Default
    private String tags = "";

    @Column(nullable = false, length = 64)
    private String name;

    @Column(name = "provider_type", nullable = false, length = 32)
    private String providerType;

    @Column(name = "api_key", length = 512)
    private String apiKey;

    @Column(name = "base_url", length = 256)
    private String baseUrl;

    @Column(columnDefinition = "TEXT")
    private String models;

    @Column(nullable = false)
    @Builder.Default
    private Boolean active = true;

    @Column(name = "is_chat_default", nullable = false)
    @Builder.Default
    private Boolean isChatDefault = false;

    @Column(name = "is_embed_default", nullable = false)
    @Builder.Default
    private Boolean isEmbedDefault = false;

    @Column(name = "is_rerank_default", nullable = false)
    @Builder.Default
    private Boolean isRerankDefault = false;

    @Column(columnDefinition = "TEXT")
    @Builder.Default
    private String capabilities = "";

    @Column(name = "context_length")
    @Builder.Default
    private Integer contextLength = 4096;

    @Column(name = "temperature")
    private Double temperature;
}
