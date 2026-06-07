package com.icusu.sivan.web;

import com.icusu.sivan.web.model.dto.EmbeddingConfigDTO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * 设置控制器集成测试。
 */
class SettingsControllerIT extends AbstractWebIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    /**
     * 嵌入配置应持久化并可回读一致。
     */
    @Test
    void shouldPersistAndReadEmbeddingConfig() {
        var config = EmbeddingConfigDTO.builder()
                .embeddingUrl("http://test-embed:8001")
                .embeddingModel("test-embed-model")
                .rerankerUrl("http://test-rerank:8002")
                .rerankerModel("test-rerank-model")
                .build();

        // PUT 持久化配置
        webTestClient.put()
                .uri("/api/settings/embedding-config")
                .bodyValue(config)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(200)
                .jsonPath("$.data.embeddingUrl").isEqualTo("http://test-embed:8001")
                .jsonPath("$.data.rerankerUrl").isEqualTo("http://test-rerank:8002");

        // GET 验证回读一致
        webTestClient.get()
                .uri("/api/settings/embedding-config")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.embeddingUrl").isEqualTo("http://test-embed:8001")
                .jsonPath("$.data.embeddingModel").isEqualTo("test-embed-model")
                .jsonPath("$.data.rerankerUrl").isEqualTo("http://test-rerank:8002")
                .jsonPath("$.data.rerankerModel").isEqualTo("test-rerank-model");
    }

    /**
     * 无持久化记录时应返回默认值。
     */
    @Test
    void shouldReturnDefaultWhenNoDbRecord() {
        // 无持久化记录时 GET 应返回默认值（非空）
        webTestClient.get()
                .uri("/api/settings/embedding-config")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(200)
                .jsonPath("$.data.embeddingUrl").isNotEmpty();
    }
}
