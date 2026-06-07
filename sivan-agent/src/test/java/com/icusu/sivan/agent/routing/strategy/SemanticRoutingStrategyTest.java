package com.icusu.sivan.agent.routing.strategy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.icusu.sivan.agent.routing.RoutingResult;
import com.icusu.sivan.agent.service.LlmService;
import com.icusu.sivan.domain.agent.AgentDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SemanticRoutingStrategyTest {

    @Mock private LlmService llmService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private SemanticRoutingStrategy strategy;
    private final UUID accountId = UUID.randomUUID();
    private final AgentDefinition coder = AgentDefinition.builder()
            .agentName("coder").description("程序员").build();
    private final AgentDefinition writer = AgentDefinition.builder()
            .agentName("writer").description("写文章").build();

    @BeforeEach
    void setUp() {
        strategy = new SemanticRoutingStrategy(llmService, objectMapper);
    }

    @Test
    void name_shouldReturnSemantic() {
        assertEquals("semantic", strategy.name());
    }

    @Test
    void route_shouldReturnNull_whenNoAgents() {
        RoutingResult result = strategy.route("test", List.of(), accountId).block();
        assertNull(result.getSelectedAgent());
        assertEquals(0.0, result.getConfidence());
    }

    @Test
    void route_shouldReturnSoloAgent_withConfidence10() {
        RoutingResult result = strategy.route("写代码", List.of(coder), accountId).block();
        assertEquals("coder", result.getSelectedAgent());
        assertEquals(1.0, result.getConfidence());
    }

    @Test
    void route_shouldReturnParsedResult_whenLlmReturnsValidJson() {
        String json = """
                {"selectedAgent": "coder", "confidence": 0.95, "reasoning": "开发任务匹配程序员"}
                """;
        when(llmService.chat(anyString(), anyString(), any())).thenReturn(Mono.just(json));

        RoutingResult result = strategy.route("写代码", List.of(coder, writer), accountId).block();
        assertEquals("coder", result.getSelectedAgent());
        assertEquals(0.95, result.getConfidence());
        assertTrue(result.getReasoning().contains("开发任务"));
    }

    @Test
    void route_shouldHandleMarkdownJson() {
        String markdownJson = """
                ```json
                {"selectedAgent": "coder", "confidence": 0.8, "reasoning": "最佳匹配"}
                ```
                """;
        when(llmService.chat(anyString(), anyString(), any())).thenReturn(Mono.just(markdownJson));

        RoutingResult result = strategy.route("写代码", List.of(coder, writer), accountId).block();
        assertEquals("coder", result.getSelectedAgent());
        assertEquals(0.8, result.getConfidence());
    }

    @Test
    void route_shouldFallback_whenLlmReturnsInvalidJson() {
        when(llmService.chat(anyString(), anyString(), any())).thenReturn(Mono.just("不确定"));

        RoutingResult result = strategy.route("写代码", List.of(coder, writer), accountId).block();
        assertEquals("coder", result.getSelectedAgent());
        assertEquals(0.0, result.getConfidence());
    }

    @Test
    void route_shouldFallback_whenLlmReturnsEmptyResponse() {
        when(llmService.chat(anyString(), anyString(), any())).thenReturn(Mono.just(""));

        RoutingResult result = strategy.route("写代码", List.of(coder, writer), accountId).block();
        assertEquals("coder", result.getSelectedAgent());
        assertEquals(0.0, result.getConfidence());
    }

    @Test
    void route_shouldFallback_whenLlmReturnsUnknownAgent() {
        String json = """
                {"selectedAgent": "unknown", "confidence": 0.9, "reasoning": "未知角色"}
                """;
        when(llmService.chat(anyString(), anyString(), any())).thenReturn(Mono.just(json));

        RoutingResult result = strategy.route("写代码", List.of(coder, writer), accountId).block();
        assertEquals("coder", result.getSelectedAgent());
        assertEquals(0.0, result.getConfidence());
    }

    @Test
    void route_shouldSelectFromMultipleAgents() {
        AgentDefinition agentA = AgentDefinition.builder().agentName("agentA").description("A").build();
        AgentDefinition agentB = AgentDefinition.builder().agentName("agentB").description("B").build();
        String json = """
                {"selectedAgent": "agentB", "confidence": 0.85, "reasoning": "更适合"}
                """;
        when(llmService.chat(anyString(), anyString(), any())).thenReturn(Mono.just(json));

        RoutingResult result = strategy.route("task", List.of(agentA, agentB), accountId).block();
        assertEquals("agentB", result.getSelectedAgent());
        assertEquals(0.85, result.getConfidence());
    }
}
