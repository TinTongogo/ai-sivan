package com.icusu.sivan.web.forest.controller;

import com.icusu.sivan.agent.model.DefaultModelRouter;
import com.icusu.sivan.core.model.Model;
import com.icusu.sivan.core.model.ModelChunk;
import com.icusu.sivan.domain.forest.ForestEvent;
import com.icusu.sivan.web.AbstractWebIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Forest SSE 端点端到端集成测试。
 * 覆盖：POST /api/v2/goals → SSE 事件流
 */
@AutoConfigureWebTestClient(timeout = "60s")
class ForestGoalE2ETest extends AbstractWebIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private DefaultModelRouter modelRouter;

    @MockitoBean
    private Model model;

    @BeforeEach
    void setUp() {
        when(modelRouter.getDefaultModel(any())).thenReturn(model);
        when(model.stream(any(), any(), any()))
                .thenReturn(Flux.just(new ModelChunk("完成", null, "stop", null, List.of())));
    }

    @Test
    void shouldStreamEventsForGoalCreation() {
        var events = webTestClient.post()
                .uri("/api/v2/goals")
                .bodyValue(Map.of("title", "测试目标", "steps", List.of("第一步")))
                .exchange()
                .expectStatus().isOk()
                .returnResult(ForestEvent.class);

        var eventList = events.getResponseBody()
                .timeout(Duration.ofSeconds(10))
                .collectList()
                .block(Duration.ofSeconds(10));

        assertNotNull(eventList, "SSE 事件流不应为空");
        assertFalse(eventList.isEmpty(), "至少应有一个事件");

        // 验证第一个是 LIFECYCLE 事件（goalEvent）
        ForestEvent first = eventList.getFirst();
        assertEquals(ForestEvent.EventType.LIFECYCLE, first.type(), "首个事件应为 LIFECYCLE");

        // 验证有 DETAIL 事件（来自 AgentLeafExecutor 的流式输出）
        boolean hasDetail = eventList.stream().anyMatch(e -> e.type() == ForestEvent.EventType.DETAIL);
        assertTrue(hasDetail, "应有 DETAIL 事件包含 Agent 输出");

        // 验证最后有 LIFECYCLE 完成事件
        boolean hasLifecycle = eventList.stream()
                .filter(e -> e.type() == ForestEvent.EventType.LIFECYCLE)
                .count() >= 2;
        assertTrue(hasLifecycle, "应有至少 2 个 LIFECYCLE 事件（开始 + 完成）");
    }

    @Test
    void shouldStreamEventsForMultipleSteps() {
        var events = webTestClient.post()
                .uri("/api/v2/goals")
                .bodyValue(Map.of("title", "多步骤目标", "steps", List.of("步骤一", "步骤二")))
                .exchange()
                .expectStatus().isOk()
                .returnResult(ForestEvent.class);

        var eventList = events.getResponseBody()
                .timeout(Duration.ofSeconds(15))
                .collectList()
                .block(Duration.ofSeconds(15));

        assertNotNull(eventList, "SSE 事件流不应为空");
        assertFalse(eventList.isEmpty(), "至少应有一个事件");

        // 验证所有事件类型均为有效类型
        for (ForestEvent event : eventList) {
            assertNotNull(event.type(), "事件类型不应为 null");
        }
    }

    @Test
    void shouldReturnProgressForGoal() {
        // 先创建目标（SSE 流）
        var events = webTestClient.post()
                .uri("/api/v2/goals")
                .bodyValue(Map.of("title", "进度测试", "steps", List.of("第一步")))
                .exchange()
                .expectStatus().isOk()
                .returnResult(ForestEvent.class);

        var eventList = events.getResponseBody()
                .timeout(Duration.ofSeconds(10))
                .collectList()
                .block(Duration.ofSeconds(10));

        assertNotNull(eventList);
        assertFalse(eventList.isEmpty());

        // 从第一个 LIFECYCLE 事件提取 goalId
        ForestEvent first = eventList.getFirst();
        String goalId = first.nodeId();
        assertNotNull(goalId, "LIFECYCLE 事件 nodeId 应为 goalId");

        // 查询进度
        webTestClient.get()
                .uri("/api/v2/goals/{goalId}/progress", goalId)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.goalId").isEqualTo(goalId)
                .jsonPath("$.progress").isNumber()
                .jsonPath("$.completed").isNumber()
                .jsonPath("$.total").isNumber();
    }

}
