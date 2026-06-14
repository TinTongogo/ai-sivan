package com.icusu.sivan.application.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Embedding / Reranker 模型运行时配置。
 * GET /api/settings/embedding-config 的响应 & PUT 的请求体共用。
 * provider 通过 tags 字段（chat/embedding/reranker）指定用途，通过 findByTag() 查询。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmbeddingConfigDTO {
    private String embeddingUrl;
    private String embeddingModel;
    private String rerankerUrl;
    private String rerankerModel;
}
