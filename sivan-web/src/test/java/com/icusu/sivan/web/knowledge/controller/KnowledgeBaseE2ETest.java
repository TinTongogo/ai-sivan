package com.icusu.sivan.web.knowledge.controller;

import com.icusu.sivan.web.AbstractWebIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 知识库控制器端到端集成测试。
 * 每个测试独立创建项目和知识库。
 */
class KnowledgeBaseE2ETest extends AbstractWebIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    private UUID createProject(String name) {
        var resp = webTestClient.post()
                .uri("/api/groups")
                .bodyValue(Map.of("name", name))
                .exchange()
                .expectBody(Map.class)
                .returnResult();
        Map<String, Object> data = extractData(resp.getResponseBody());
        return UUID.fromString((String) data.get("projectId"));
    }

    @Test
    void shouldCreateAndGetKnowledgeBase() {
        UUID pid = createProject("KB创建项目");
        webTestClient.post()
                .uri("/api/v2/knowledge-bases")
                .bodyValue(Map.of("kbName", "kb-create", "description", "描述", "projectId", pid))
                .exchange()
                .expectBody()
                .jsonPath("$.code").isEqualTo(201);

        webTestClient.get()
                .uri("/api/v2/knowledge-bases/kb-create")
                .exchange()
                .expectBody()
                .jsonPath("$.code").isEqualTo(200)
                .jsonPath("$.data.kbName").isEqualTo("kb-create");
    }

    @Test
    void shouldListKnowledgeBases() {
        UUID pid = createProject("KB列项目");
        webTestClient.post()
                .uri("/api/v2/knowledge-bases")
                .bodyValue(Map.of("kbName", "kb-list-a", "projectId", pid))
                .exchange()
                .expectBody()
                .jsonPath("$.code").isEqualTo(201);

        webTestClient.get()
                .uri("/api/v2/knowledge-bases")
                .exchange()
                .expectBody()
                .jsonPath("$.code").isEqualTo(200)
                .jsonPath("$.data.length()").isNotEmpty();
    }

    @Test
    void shouldUploadAndListDocuments() {
        UUID pid = createProject("KB文档项目");
        webTestClient.post()
                .uri("/api/v2/knowledge-bases")
                .bodyValue(Map.of("kbName", "kb-docs", "projectId", pid))
                .exchange()
                .expectBody()
                .jsonPath("$.code").isEqualTo(201);

        webTestClient.post()
                .uri("/api/v2/knowledge-bases/kb-docs/documents")
                .bodyValue(Map.of("filename", "test.md", "textContent", "# Test", "projectId", pid))
                .exchange()
                .expectBody()
                .jsonPath("$.code").isEqualTo(201);

        webTestClient.get()
                .uri("/api/v2/knowledge-bases/kb-docs/documents")
                .exchange()
                .expectBody()
                .jsonPath("$.code").isEqualTo(200)
                .jsonPath("$.data.length()").isNotEmpty();
    }

    @Test
    void shouldDeleteKnowledgeBase() {
        UUID pid = createProject("KB删除项目");
        webTestClient.post()
                .uri("/api/v2/knowledge-bases")
                .bodyValue(Map.of("kbName", "kb-to-delete", "projectId", pid))
                .exchange()
                .expectBody()
                .jsonPath("$.code").isEqualTo(201);

        webTestClient.delete()
                .uri("/api/v2/knowledge-bases/kb-to-delete")
                .exchange()
                .expectBody()
                .jsonPath("$.code").isEqualTo(200);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractData(Map<String, Object> body) {
        assertNotNull(body);
        assertNotNull(body.get("data"));
        return (Map<String, Object>) body.get("data");
    }
}
