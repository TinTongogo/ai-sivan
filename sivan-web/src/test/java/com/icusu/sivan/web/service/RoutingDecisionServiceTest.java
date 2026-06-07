package com.icusu.sivan.web.routing.service;

import com.icusu.sivan.common.exception.DomainException;
import com.icusu.sivan.domain.routing.RoutingDecision;
import com.icusu.sivan.domain.routing.IRoutingDecisionRepository;
import com.icusu.sivan.web.routing.dto.CreateRoutingDecisionRequest;
import com.icusu.sivan.web.routing.dto.RoutingDecisionResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
/** 路由决策服务测试。 */
class RoutingDecisionServiceTest {

    @Mock
    private IRoutingDecisionRepository routingDecisionRepository;

    private RoutingDecisionService routingDecisionService;

    private final UUID accountId = UUID.randomUUID();

    @BeforeEach
    /** 初始化测试环境。 */
    void setUp() {
        routingDecisionService = new RoutingDecisionService(routingDecisionRepository);
    }

    @Test
    /** 创建路由决策成功。 */
    void create_shouldSucceed() {
        CreateRoutingDecisionRequest request = new CreateRoutingDecisionRequest();
        request.setTaskDescription("代码审查");
        request.setSelectedAgentName("reviewer");
        request.setStrategy("llm");
        request.setConfidence(0.95);
        request.setReasoning("最合适的 Agent");

        RoutingDecisionResponse response = routingDecisionService.create(accountId, request);

        assertEquals("代码审查", response.getTaskDescription());
        assertEquals("reviewer", response.getSelectedAgentName());
        assertEquals("llm", response.getStrategy());
        assertEquals(0.95, response.getConfidence());
        assertFalse(response.getSuccess());
        verify(routingDecisionRepository).save(any(RoutingDecision.class));
    }

    @Test
    /** 根据 ID 获取路由决策。 */
    void getById_shouldReturnDecision() {
        UUID decisionId = UUID.randomUUID();
        RoutingDecision decision = RoutingDecision.builder()
                .decisionId(decisionId).accountId(accountId)
                .taskDescription("测试任务").selectedAgentName("agent-x").build();

        when(routingDecisionRepository.findById(decisionId)).thenReturn(Optional.of(decision));

        RoutingDecisionResponse response = routingDecisionService.getById(decisionId, accountId);

        assertEquals("测试任务", response.getTaskDescription());
        assertEquals("agent-x", response.getSelectedAgentName());
    }

    @Test
    /** 获取不存在的路由决策应抛出异常。 */
    void getById_shouldThrowWhenNotFound() {
        UUID decisionId = UUID.randomUUID();
        when(routingDecisionRepository.findById(decisionId)).thenReturn(Optional.empty());

        assertThrows(DomainException.class,
                () -> routingDecisionService.getById(decisionId, accountId));
    }

    @Test
    /** 列出所有路由决策。 */
    void list_shouldReturnAllWhenNoStrategy() {
        RoutingDecision d = RoutingDecision.builder()
                .decisionId(UUID.randomUUID()).accountId(accountId).taskDescription("任务1").build();

        when(routingDecisionRepository.findByAccount(accountId)).thenReturn(List.of(d));

        List<RoutingDecisionResponse> list = routingDecisionService.list(accountId, null);

        assertEquals(1, list.size());
        assertEquals("任务1", list.get(0).getTaskDescription());
        verify(routingDecisionRepository).findByAccount(accountId);
    }

    @Test
    /** 按策略过滤路由决策。 */
    void list_shouldFilterByStrategy() {
        String strategy = "llm";
        when(routingDecisionRepository.findByAccountAndStrategy(accountId, strategy))
                .thenReturn(List.of());

        List<RoutingDecisionResponse> list = routingDecisionService.list(accountId, strategy);

        assertTrue(list.isEmpty());
        verify(routingDecisionRepository).findByAccountAndStrategy(accountId, strategy);
    }
}
