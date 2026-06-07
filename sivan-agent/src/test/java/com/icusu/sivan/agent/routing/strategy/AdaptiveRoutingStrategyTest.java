package com.icusu.sivan.agent.routing.strategy;

import com.icusu.sivan.agent.routing.RoutingResult;
import com.icusu.sivan.domain.agent.AgentDefinition;
import com.icusu.sivan.domain.routing.IRoutingDecisionRepository;
import com.icusu.sivan.domain.routing.RoutingDecision;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdaptiveRoutingStrategyTest {

    @Mock private IRoutingDecisionRepository repository;

    private AdaptiveRoutingStrategy strategy;
    private final UUID accountId = UUID.randomUUID();
    private final AgentDefinition coder = AgentDefinition.builder()
            .agentName("coder").description("写代码").build();
    private final AgentDefinition writer = AgentDefinition.builder()
            .agentName("writer").description("写文章").build();
    private final AgentDefinition designer = AgentDefinition.builder()
            .agentName("designer").description("做设计").build();

    @BeforeEach
    void setUp() {
        strategy = new AdaptiveRoutingStrategy(repository);
    }

    @Test
    void name_shouldReturnAdaptive() {
        assertEquals("adaptive", strategy.name());
    }

    @Test
    void route_shouldReturnNull_whenNoAgents() {
        RoutingResult result = strategy.route("test", List.of(), accountId).block();
        assertNull(result.getSelectedAgent());
        assertEquals(0.0, result.getConfidence());
    }

    @Test
    void route_shouldReturnSoloAgent_withConfidence07() {
        RoutingResult result = strategy.route("写代码", List.of(coder), accountId).block();
        assertEquals("coder", result.getSelectedAgent());
        assertEquals(0.7, result.getConfidence());
    }

    @Test
    void route_shouldUseBaseScore_whenNoHistory() {
        lenient().when(repository.findByAccount(any())).thenReturn(List.of());

        RoutingResult result = strategy.route("写代码", List.of(coder, writer), accountId).block();
        assertNotNull(result.getSelectedAgent());
        assertTrue(result.getConfidence() >= 0.4);
    }

    @Test
    void route_shouldPreferHighSuccessRate() {
        RoutingDecision success1 = RoutingDecision.builder()
                .selectedAgentName("coder").success(true).build();
        RoutingDecision success2 = RoutingDecision.builder()
                .selectedAgentName("coder").success(true).build();
        RoutingDecision fail1 = RoutingDecision.builder()
                .selectedAgentName("writer").success(false).build();
        when(repository.findByAccount(any())).thenReturn(List.of(success1, success2, fail1));

        RoutingResult result = strategy.route("写代码", List.of(coder, writer), accountId).block();
        assertEquals("coder", result.getSelectedAgent());
    }

    @Test
    void route_shouldPenalizeDomainMismatch() {
        // designer 有 100% 成功率，但 0 词汇重叠
        RoutingDecision d1 = RoutingDecision.builder()
                .selectedAgentName("designer").success(true).build();
        RoutingDecision d2 = RoutingDecision.builder()
                .selectedAgentName("designer").success(true).build();
        // coder 有 0 历史 → base score 0.4
        when(repository.findByAccount(any())).thenReturn(List.of(d1, d2));

        // 任务描述 "写文章" 与 coder（"写代码"）部分重叠，但与 designer（"做设计"）无重叠
        RoutingResult result = strategy.route("写文章", List.of(coder, designer), accountId).block();
        // designer 的分受领域不匹配惩罚（×0.5），coder 无历史得 base 0.4
        // 所以 coder 胜出
        assertEquals("coder", result.getSelectedAgent());
    }

    @Test
    void route_shouldConsiderSampleSize() {
        // coder: 3 次都成功 (100%, 样本量 3)
        // writer: 2 次中 1 次成功 (50%, 样本量 2)
        RoutingDecision c1 = RoutingDecision.builder().selectedAgentName("coder").success(true).build();
        RoutingDecision c2 = RoutingDecision.builder().selectedAgentName("coder").success(true).build();
        RoutingDecision c3 = RoutingDecision.builder().selectedAgentName("coder").success(true).build();
        RoutingDecision w1 = RoutingDecision.builder().selectedAgentName("writer").success(true).build();
        RoutingDecision w2 = RoutingDecision.builder().selectedAgentName("writer").success(false).build();

        when(repository.findByAccount(any())).thenReturn(List.of(c1, c2, c3, w1, w2));

        RoutingResult result = strategy.route("写代码", List.of(coder, writer), accountId).block();
        assertEquals("coder", result.getSelectedAgent());
    }
}
