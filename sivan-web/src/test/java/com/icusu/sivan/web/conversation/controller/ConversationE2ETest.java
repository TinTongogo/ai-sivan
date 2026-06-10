package com.icusu.sivan.web.conversation.controller;

import com.icusu.sivan.web.AbstractWebIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 对话控制器端到端集成测试。
 * 覆盖完整的对话生命周期：创建项目 → 创建对话 → 发送消息 → 查询 → 评价 → 删除。
 */
class ConversationE2ETest extends AbstractWebIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    private UUID projectId;

    @BeforeEach
    void setUp() {
        // 先创建项目（对话依赖项目）
        var resp = webTestClient.post()
                .uri("/api/groups")
                .bodyValue(Map.of("name", "测试项目", "description", "E2E 测试项目"))
                .exchange()
                .expectBody(Map.class)
                .returnResult();
        Map<String, Object> data = extractData(resp.getResponseBody());
        projectId = UUID.fromString((String) data.get("projectId"));
    }

    /** 创建新会话并返回 ID。 */
    private UUID createConversation(String title) {
        var resp = webTestClient.post()
                .uri("/api/v2/conversations")
                .bodyValue(Map.of("title", title, "projectId", projectId.toString()))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(Map.class)
                .returnResult();
        Map<String, Object> data = extractData(resp.getResponseBody());
        return UUID.fromString((String) data.get("conversationId"));
    }

    /** 发送消息并返回 messageId。 */
    private String sendMessage(UUID convId, String content) {
        var resp = webTestClient.post()
                .uri("/api/v2/conversations/{id}/messages", convId)
                .bodyValue(Map.of("content", content, "projectId", projectId.toString()))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(Map.class)
                .returnResult();
        Map<String, Object> data = extractData(resp.getResponseBody());
        return (String) data.get("messageId");
    }

    @Test
    void shouldCreateAndGetConversation() {
        var id = createConversation("测试对话");

        webTestClient.get()
                .uri("/api/v2/conversations/{id}", id)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(200)
                .jsonPath("$.data.conversationId").isEqualTo(id.toString())
                .jsonPath("$.data.title").isEqualTo("测试对话");
    }

    @Test
    void shouldListConversations() {
        createConversation("列表测试");

        webTestClient.get()
                .uri("/api/v2/conversations")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(200)
                .jsonPath("$.data.length()").isNotEmpty();
    }

    @Test
    void shouldUpdateConversation() {
        var id = createConversation("旧标题");

        webTestClient.put()
                .uri("/api/v2/conversations/{id}", id)
                .bodyValue(Map.of("title", "新标题"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(200)
                .jsonPath("$.data.title").isEqualTo("新标题");
    }

    @Test
    void shouldSendAndGetMessages() {
        var id = createConversation("消息测试");

        webTestClient.post()
                .uri("/api/v2/conversations/{id}/messages", id)
                .bodyValue(Map.of("content", "你好，这是一条测试消息", "projectId", projectId.toString()))
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.code").isEqualTo(201)
                .jsonPath("$.data.messageId").isNotEmpty()
                .jsonPath("$.data.content").isEqualTo("你好，这是一条测试消息");

        webTestClient.get()
                .uri("/api/v2/conversations/{id}/messages?limit=10", id)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(200)
                .jsonPath("$.data.messages.length()").isNotEmpty();
    }

    @Test
    void shouldRateMessage() {
        var id = createConversation("评价测试");
        String msgId = sendMessage(id, "待评价的消息");

        webTestClient.patch()
                .uri("/api/v2/conversations/messages/{msgId}/rating?rating=like", msgId)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(200);
    }

    @Test
    void shouldDeleteMessage() {
        var id = createConversation("删除测试");
        String msgId = sendMessage(id, "待删除的消息");

        webTestClient.delete()
                .uri("/api/v2/conversations/messages/{msgId}", msgId)
                .exchange()
                .expectStatus().isNoContent();
    }

    @Test
    void shouldDeleteConversation() {
        var id = createConversation("待删除的会话");

        webTestClient.delete()
                .uri("/api/v2/conversations/{id}", id)
                .exchange()
                .expectStatus().isNoContent();

        webTestClient.get()
                .uri("/api/v2/conversations/{id}", id)
                .exchange()
                .expectStatus().is4xxClientError();
    }

    // ====== 辅助 ======

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractData(Map<String, Object> body) {
        assertNotNull(body);
        assertNotNull(body.get("data"));
        return (Map<String, Object>) body.get("data");
    }
}
