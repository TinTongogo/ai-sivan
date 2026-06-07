package com.icusu.sivan.web.orchestration.controller;

import com.icusu.sivan.core.model.Model;
import com.icusu.sivan.web.AbstractWebIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Squad 自动编排端到端集成测试。
 * 覆盖：创建 Squad → 执行 → 查询执行状态 → 查看阶段。
 */
class SquadOrchestrationE2ETest extends AbstractWebIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private Model model;

    private UUID projectId;

    @BeforeEach
    void setUp() {
        when(model.modelId()).thenReturn("test-model");
        when(model.chat(any(), any())).thenReturn(Mono.just(
                new Model.ModelResponse(null, null)));
        lenient().when(model.chat(any(), any(), any())).thenReturn(Mono.just(
                new Model.ModelResponse(null, null)));

        var resp = webTestClient.post()
                .uri("/api/groups")
                .bodyValue(Map.of("name", "编排测试项目"))
                .exchange()
                .expectBody(Map.class)
                .returnResult();
        Map<String, Object> data = extractData(resp.getResponseBody());
        projectId = UUID.fromString((String) data.get("projectId"));
    }

    @Test
    void shouldCreateAndExecuteSquad() {
        // 创建 Squad
        var createResp = webTestClient.post()
                .uri("/api/squads")
                .bodyValue(Map.of(
                        "name", "测试编排",
                        "description", "E2E 编排测试",
                        "projectId", projectId.toString(),
                        "mode", "SEQUENTIAL",
                        "phases", java.util.List.of(
                                Map.of("agents", java.util.List.of("agent1"), "mode", "SEQUENTIAL")
                        )
                ))
                .exchange()
                .expectBody(Map.class)
                .returnResult();
        Map<String, Object> squadData = extractData(createResp.getResponseBody());
        UUID squadId = UUID.fromString((String) squadData.get("squadId"));

        // 执行 Squad
        webTestClient.post()
                .uri("/api/squads/{squadId}/execute", squadId)
                .bodyValue(Map.of("taskDescription", "E2E 测试任务"))
                .exchange()
                .expectStatus().isAccepted()
                .expectBody()
                .jsonPath("$.code").isEqualTo(202)
                .jsonPath("$.data.executionId").isNotEmpty();
    }

    @Test
    void shouldListExecutions() {
        // 创建并执行 Squad
        var squadResp = webTestClient.post()
                .uri("/api/squads")
                .bodyValue(Map.of(
                        "name", "执行列表测试",
                        "projectId", projectId.toString(),
                        "mode", "SEQUENTIAL",
                        "phases", java.util.List.of(
                                Map.of("agents", java.util.List.of("agent1"), "mode", "SEQUENTIAL")
                        )
                ))
                .exchange()
                .expectBody(Map.class)
                .returnResult();
        UUID squadId = UUID.fromString((String) extractData(squadResp.getResponseBody()).get("squadId"));

        webTestClient.post()
                .uri("/api/squads/{squadId}/execute", squadId)
                .bodyValue(Map.of("taskDescription", "列表测试"))
                .exchange()
                .expectStatus().isAccepted();

        // 查询执行列表
        webTestClient.get()
                .uri("/api/squads/{squadId}/executions", squadId)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(200)
                .jsonPath("$.data.items.length()").isNotEmpty();
    }

    @Test
    void shouldListSquads() {
        webTestClient.post()
                .uri("/api/squads")
                .bodyValue(Map.of(
                        "name", "列表测试Squad",
                        "projectId", projectId.toString(),
                        "mode", "SEQUENTIAL"
                ))
                .exchange()
                .expectBody()
                .jsonPath("$.code").isEqualTo(201);

        webTestClient.get()
                .uri("/api/squads")
                .exchange()
                .expectBody()
                .jsonPath("$.code").isEqualTo(200)
                .jsonPath("$.data.length()").isNotEmpty();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractData(Map<String, Object> body) {
        var data = (Map<String, Object>) body.get("data");
        if (data == null) throw new AssertionError("Response missing data field: " + body);
        return data;
    }
}
