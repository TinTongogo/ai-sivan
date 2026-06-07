package com.icusu.sivan.agent.routing;

import com.icusu.sivan.domain.routing.IRoutingDecisionRepository;
import com.icusu.sivan.domain.routing.RoutingDecision;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DefaultRoutingDecisionRecorderTest {

    @Mock private IRoutingDecisionRepository repository;
    @Captor private ArgumentCaptor<RoutingDecision> decisionCaptor;

    private DefaultRoutingDecisionRecorder recorder;
    private final UUID accountId = UUID.randomUUID();
    private final UUID conversationId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        recorder = new DefaultRoutingDecisionRecorder(repository);
    }

    @Test
    void record_shouldSaveAndReturnDecisionId() {
        var request = new RoutingDecisionRecorder.RecordRequest(
                accountId, null, conversationId, "测试任务",
                "ml", "coder", true, "匹配成功", 0.9, null, Map.of("key", "val"));

        UUID result = recorder.record(request);
        assertNull(result); // 实体 decisionId 为 null 未回写

        verify(repository).save(decisionCaptor.capture());
        RoutingDecision saved = decisionCaptor.getValue();
        assertEquals(accountId, saved.getAccountId());
        assertEquals("ml", saved.getStrategy());
        assertEquals("coder", saved.getSelectedAgentName());
        assertTrue(saved.getSuccess());
        assertEquals(0.9, saved.getConfidence());
        assertEquals("匹配成功", saved.getReasoning());
    }

    @Test
    void record_shouldCatchExceptionAndReturnNull() {
        doThrow(new RuntimeException("DB 异常")).when(repository).save(any());

        var request = RoutingDecisionRecorder.RecordRequest.simple(
                accountId, null, conversationId, "test", "adaptive", "writer", true, "ok", null);

        assertNull(recorder.record(request));
    }

    @Test
    void record_shouldTruncateLongTaskDescription() {
        String longDesc = "a".repeat(1000);
        var request = new RoutingDecisionRecorder.RecordRequest(
                accountId, null, conversationId, longDesc,
                "semantic", "agentX", false, null, null, null, null);

        recorder.record(request);
        verify(repository).save(decisionCaptor.capture());
        String saved = decisionCaptor.getValue().getTaskDescription();
        assertEquals(500 + 3, saved.length()); // 500 + "..."
        assertTrue(saved.endsWith("..."));
    }

    @Test
    void record_shouldNotTruncateShortTaskDescription() {
        var request = new RoutingDecisionRecorder.RecordRequest(
                accountId, null, conversationId, "你好",
                "auto", "bot", true, null, 1.0, null, null);

        recorder.record(request);
        verify(repository).save(decisionCaptor.capture());
        assertEquals("你好", decisionCaptor.getValue().getTaskDescription());
    }

    @Test
    void record_shouldHandleNullTaskDescription() {
        var request = new RoutingDecisionRecorder.RecordRequest(
                accountId, null, conversationId, null,
                "auto", "bot", true, null, null, null, null);

        recorder.record(request);
        verify(repository).save(decisionCaptor.capture());
        assertEquals("", decisionCaptor.getValue().getTaskDescription());
    }

    @Test
    void simple_shouldSetNullConfidenceAndErrorHint() {
        var request = RoutingDecisionRecorder.RecordRequest.simple(
                accountId, UUID.randomUUID(), conversationId, "task",
                "ml", "coder", true, "ok", Map.of("k", "v"));

        assertNull(request.confidence());
        assertNull(request.errorHint());
        assertEquals(accountId, request.accountId());
        assertEquals("ml", request.strategy());
        assertEquals("coder", request.selectedAgentName());
        assertTrue(request.success());
    }
}
