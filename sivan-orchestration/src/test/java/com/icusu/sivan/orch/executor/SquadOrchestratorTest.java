package com.icusu.sivan.orch.executor;

import com.icusu.sivan.orch.intent.LocalIntentClassifier;
import com.icusu.sivan.orch.topology.ExecutionPathResolver;
import com.icusu.sivan.orch.topology.ExecutionPathResult;
import com.icusu.sivan.agent.model.ModelRouter;
import com.icusu.sivan.agent.routing.RoutingDecisionRecorder;
import com.icusu.sivan.agent.service.TokenUsageRecorder;
import com.icusu.sivan.common.enums.Intent;
import com.icusu.sivan.core.message.Content;
import com.icusu.sivan.core.message.Msg;
import com.icusu.sivan.core.message.Role;
import com.icusu.sivan.core.model.Model;
import com.icusu.sivan.core.model.Model.ModelParams;
import com.icusu.sivan.core.model.Model.ModelResponse;
import com.icusu.sivan.domain.task.ExecutionPath;
import com.icusu.sivan.domain.task.ExecutionShape;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * SquadOrchestrator 单元测试。
 * 聚焦意图解析（resolveIntent）的分支行为：模板命中/未命中/探索。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SquadOrchestratorTest {

    @Mock
    private RoutingDecisionRecorder routingDecisionRecorder;
    @Mock
    private ModelRouter modelRouter;
    @Mock
    private ExecutionPathResolver executionPathResolver;
    @Mock
    private OrchestrationDispatcher dispatcher;
    @Mock
    private TokenUsageRecorder tokenUsageRecorder;

    @Mock
    private LocalIntentClassifier localIntentClassifier;

    private SquadOrchestrator orchestrator;
    private final UUID accountId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final UUID conversationId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        orchestrator = new SquadOrchestrator(
                dispatcher, modelRouter, executionPathResolver, routingDecisionRecorder, tokenUsageRecorder, localIntentClassifier
        );
        // 默认本地分类返回 null（低置信度），由 LLM classify 决定
        when(localIntentClassifier.classify(any())).thenReturn(null);
    }

    /** 模板命中且未探索 → 直接返回 intent，不调用 classify。 */
    @Test
    void resolveIntent_shouldSkipClassify_whenTemplateHitNoExploration() {
        ExecutionPath path = new ExecutionPath(ExecutionShape.SQUAD, null, null, null, null);
        ExecutionPathResult result = new ExecutionPathResult(path, true, UUID.randomUUID(), true);
        when(executionPathResolver.resolve(anyString(), eq(accountId))).thenReturn(result);

        Intent intent = orchestrator.resolveIntent("任务描述", accountId, projectId, conversationId).block();

        assertEquals(Intent.SQUAD, intent);
        verify(routingDecisionRecorder).record(any());
        verify(modelRouter, never()).getDefaultModel(any());
    }

    /** 模板命中且探索 → 降级 LLM classify。 */
    @Test
    void resolveIntent_shouldCallClassify_whenTemplateHitWithExploration() {
        ExecutionPath path = new ExecutionPath(ExecutionShape.SQUAD, null, null, null, null);
        ExecutionPathResult result = new ExecutionPathResult(path, true, UUID.randomUUID(), false);
        when(executionPathResolver.resolve(anyString(), eq(accountId))).thenReturn(result);
        mockClassify(Intent.CHAT);

        Intent intent = orchestrator.resolveIntent("需要多步协作完成的任务", accountId, projectId, conversationId).block();

        assertEquals(Intent.CHAT, intent);
    }

    /** 模板未命中 → 调用 LLM classify。 */
    @Test
    void resolveIntent_shouldCallClassify_whenNoTemplate() {
        ExecutionPathResult result = ExecutionPathResult.noMatch();
        when(executionPathResolver.resolve(anyString(), eq(accountId))).thenReturn(result);
        mockClassify(Intent.SINGLE_AGENT);

        Intent intent = orchestrator.resolveIntent("需要多步协作完成的任务", accountId, projectId, conversationId).block();

        assertEquals(Intent.SINGLE_AGENT, intent);
    }

    /** 模板命中 CHAT 模式 → 返回 CHAT intent。 */
    @Test
    void resolveIntent_chatShape_shouldReturnChat() {
        ExecutionPath path = new ExecutionPath(ExecutionShape.CHAT, null, null, null, null);
        ExecutionPathResult result = new ExecutionPathResult(path, true, UUID.randomUUID(), true);
        when(executionPathResolver.resolve(anyString(), eq(accountId))).thenReturn(result);

        Intent intent = orchestrator.resolveIntent("你好", accountId, projectId, conversationId).block();

        assertEquals(Intent.CHAT, intent);
    }

    /** 模板命中 SINGLE_AGENT → 返回 SINGLE_AGENT intent。 */
    @Test
    void resolveIntent_singleAgentShape_shouldReturnSingleAgent() {
        ExecutionPath path = new ExecutionPath(ExecutionShape.SINGLE_AGENT, null, null, null, null);
        ExecutionPathResult result = new ExecutionPathResult(path, true, UUID.randomUUID(), true);
        when(executionPathResolver.resolve(anyString(), eq(accountId))).thenReturn(result);

        Intent intent = orchestrator.resolveIntent("帮我写份报告", accountId, projectId, conversationId).block();

        assertEquals(Intent.SINGLE_AGENT, intent);
    }

    /** LLM classify 异常时降级为 CHAT。 */
    @Test
    void resolveIntent_shouldFallbackToChat_whenClassifyFails() {
        ExecutionPathResult result = ExecutionPathResult.noMatch();
        when(executionPathResolver.resolve(anyString(), eq(accountId))).thenReturn(result);
        var model = mock(Model.class);
        when(modelRouter.getDefaultModel(any())).thenReturn(model);
        when(model.chat(anyList(), any(ModelParams.class))).thenReturn(Mono.error(new RuntimeException("LLM 不可用")));

        Intent intent = orchestrator.resolveIntent("需要多步协作完成的任务", accountId, projectId, conversationId).block();

        assertEquals(Intent.CHAT, intent);
    }

    // ===== 辅助方法 =====

    private void mockClassify(Intent intent) {
        var model = mock(Model.class);
        when(modelRouter.getDefaultModel(any())).thenReturn(model);
        var response = new ModelResponse(
                Msg.of(Role.ASSISTANT, List.of(new Content.Text(intent.name()))), null);
        when(model.chat(anyList(), any(ModelParams.class))).thenReturn(Mono.just(response));
    }
}
