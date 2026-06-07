package com.icusu.sivan.agent.routing.strategy;

import com.icusu.sivan.agent.routing.RoutingResult;
import com.icusu.sivan.domain.agent.AgentDefinition;
import com.icusu.sivan.domain.shared.port.IEmbeddingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MLRoutingStrategyTest {

    @Mock private IEmbeddingService embeddingService;

    private MLRoutingStrategy strategy;
    private final UUID accountId = UUID.randomUUID();
    private final AgentDefinition coder = AgentDefinition.builder()
            .agentName("coder").description("写代码").category("dev").build();
    private final AgentDefinition writer = AgentDefinition.builder()
            .agentName("writer").description("写文章").category("content").build();

    @BeforeEach
    void setUp() {
        strategy = new MLRoutingStrategy(embeddingService);
    }

    @Test
    void name_shouldReturnMl() {
        assertEquals("ml", strategy.name());
    }

    @Test
    void route_shouldReturnNull_whenNoAgents() {
        RoutingResult result = strategy.route("test", List.of(), accountId).block();
        assertNull(result.getSelectedAgent());
        assertEquals(0.0, result.getConfidence());
    }

    @Test
    void route_shouldReturnSoloAgent_withConfidence06() {
        RoutingResult result = strategy.route("写代码", List.of(coder), accountId).block();
        assertEquals("coder", result.getSelectedAgent());
        assertEquals(0.6, result.getConfidence());
    }

    @Test
    void route_shouldUseEmbedding_whenAvailable() {
        when(embeddingService.embed(anyString())).thenReturn(new float[]{1.0f, 0.0f});
        when(embeddingService.embedBatch(anyList())).thenReturn(
                List.of(new float[]{0.9f, 0.1f}, new float[]{0.2f, 0.8f}));

        RoutingResult result = strategy.route("写代码", List.of(coder, writer), accountId).block();
        assertEquals("coder", result.getSelectedAgent());
        assertTrue(result.getConfidence() > 0);
    }

    @Test
    void route_shouldFallbackToKeyword_whenEmbeddingThrows() {
        when(embeddingService.embed(anyString())).thenThrow(new RuntimeException("服务不可用"));

        RoutingResult result = strategy.route("写代码", List.of(coder, writer), accountId).block();
        assertNotNull(result.getSelectedAgent());
        assertTrue(result.getConfidence() >= 0);
    }

    @Test
    void route_keyword_shouldMatchByName() {
        when(embeddingService.embed(anyString())).thenThrow(new RuntimeException("不可用"));

        RoutingResult result = strategy.route("找coder处理任务", List.of(coder, writer), accountId).block();
        assertEquals("coder", result.getSelectedAgent());
    }

    @Test
    void route_keyword_shouldMatchByCategory() {
        when(embeddingService.embed(anyString())).thenThrow(new RuntimeException("不可用"));
        AgentDefinition devAgent = AgentDefinition.builder()
                .agentName("dev").description("开发").category("dev").build();
        AgentDefinition contentAgent = AgentDefinition.builder()
                .agentName("content").description("内容").category("content").build();

        RoutingResult result = strategy.route("dev任务", List.of(devAgent, contentAgent), accountId).block();
        assertEquals("dev", result.getSelectedAgent());
    }

    @Test
    void route_embedding_shouldSelectBestMatch() {
        when(embeddingService.embed(anyString())).thenReturn(new float[]{1.0f, 0.0f, 0.5f});
        when(embeddingService.embedBatch(anyList())).thenReturn(List.of(
                new float[]{0.9f, 0.1f, 0.4f},   // 与 task 相似度高
                new float[]{0.1f, 0.9f, 0.1f},   // 与 task 相似度低
                new float[]{0.3f, 0.3f, 0.8f}    // 中等
        ));

        AgentDefinition agentA = AgentDefinition.builder().agentName("agentA").description("A").build();
        AgentDefinition agentB = AgentDefinition.builder().agentName("agentB").description("B").build();
        AgentDefinition agentC = AgentDefinition.builder().agentName("agentC").description("C").build();

        RoutingResult result = strategy.route("task", List.of(agentA, agentB, agentC), accountId).block();
        assertEquals("agentA", result.getSelectedAgent());
    }

    @Test
    void route_keyword_shouldDefaultToFirst_whenNoMatch() {
        when(embeddingService.embed(anyString())).thenThrow(new RuntimeException("不可用"));
        AgentDefinition a1 = AgentDefinition.builder().agentName("alpha").build();
        AgentDefinition a2 = AgentDefinition.builder().agentName("beta").build();

        // 无关键词匹配任何 agent
        RoutingResult result = strategy.route("zzz未知任务", List.of(a1, a2), accountId).block();
        assertEquals("alpha", result.getSelectedAgent());
    }
}
