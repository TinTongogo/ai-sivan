package com.icusu.sivan.web.goal.controller;

import com.icusu.sivan.core.model.Model;
import com.icusu.sivan.domain.goal.Goal;
import com.icusu.sivan.domain.goal.Milestone;
import com.icusu.sivan.orch.scheduler.GoalDecomposer;
import com.icusu.sivan.web.AbstractWebIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;


import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 自主任务端到端集成测试。
 * 覆盖：创建目标 → 查询 → 暂停 → 取消的完整生命周期。
 */
class GoalE2ETest extends AbstractWebIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private Model model;

    @MockitoBean
    private GoalDecomposer goalDecomposer;

    private UUID projectId;

    @BeforeEach
    void setUp() {
        when(model.modelId()).thenReturn("test-model");
        when(model.chat(any(), any())).thenReturn(Mono.just(
                new Model.ModelResponse(null, null)));

        var resp = webTestClient.post()
                .uri("/api/groups")
                .bodyValue(Map.of("name", "目标任务测试项目"))
                .exchange()
                .expectBody(Map.class)
                .returnResult();
        Map<String, Object> data = extractData(resp.getResponseBody());
        projectId = UUID.fromString((String) data.get("projectId"));

        lenient().when(goalDecomposer.decompose(any(), any(), any(), any()))
                .thenReturn(Mono.just(Goal.builder()
                        .title("测试目标")
                        .description("E2E 目标测试")
                        .accountId(TEST_ACCOUNT_ID)
                        .totalTasks(2).completedTasks(0)
                        .projectId(projectId)
                        .milestones(List.of(Milestone.builder().name("阶段1").order(1).tasks(List.of()).build()))
                        .build()));
    }

    @Test
    void shouldCreateAndGetGoal() {
        var resp = webTestClient.post()
                .uri("/api/goals")
                .bodyValue(Map.of(
                        "title", "测试目标",
                        "description", "E2E 目标测试",
                        "projectId", projectId.toString()
                ))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(Map.class)
                .returnResult();
        Map<String, Object> goalData = extractData(resp.getResponseBody());
        UUID goalId = UUID.fromString((String) goalData.get("goalId"));

        webTestClient.get()
                .uri("/api/goals/{goalId}", goalId)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(200)
                .jsonPath("$.data.title").isEqualTo("测试目标");
    }

    @Test
    void shouldListGoals() {
        webTestClient.post()
                .uri("/api/goals")
                .bodyValue(Map.of("title", "目标A", "projectId", projectId.toString()))
                .exchange()
                .expectStatus().isCreated();

        webTestClient.get()
                .uri("/api/goals")
                .exchange()
                .expectBody()
                .jsonPath("$.code").isEqualTo(200)
                .jsonPath("$.data.length()").isNotEmpty();
    }

    @Test
    void shouldPauseAndCancelGoal() {
        var resp = webTestClient.post()
                .uri("/api/goals")
                .bodyValue(Map.of(
                        "title", "可控目标",
                        "projectId", projectId.toString()
                ))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(Map.class)
                .returnResult();
        UUID goalId = UUID.fromString((String) extractData(resp.getResponseBody()).get("goalId"));

        webTestClient.post()
                .uri("/api/goals/{goalId}/pause", goalId)
                .bodyValue(Map.of("reason", "手动暂停测试"))
                .exchange()
                .expectBody()
                .jsonPath("$.code").isEqualTo(200);

        webTestClient.post()
                .uri("/api/goals/{goalId}/cancel", goalId)
                .exchange()
                .expectBody()
                .jsonPath("$.code").isEqualTo(200);
    }

    @Test
    void shouldGetProgressByConversation() {
        webTestClient.post()
                .uri("/api/goals")
                .bodyValue(Map.of(
                        "title", "进度查询测试",
                        "projectId", projectId.toString()
                ))
                .exchange()
                .expectStatus().isCreated();

        // 查询进度（无关联 conversation 时返回空数据）
        webTestClient.get()
                .uri("/api/goals/by-conversation/{convId}", UUID.randomUUID())
                .exchange()
                .expectBody()
                .jsonPath("$.code").isEqualTo(200);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractData(Map<String, Object> body) {
        var data = (Map<String, Object>) body.get("data");
        if (data == null) throw new AssertionError("Response missing data field: " + body);
        return data;
    }
}
