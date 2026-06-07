package com.icusu.sivan.agent.service;

import com.icusu.sivan.agent.AgentResult;
import com.icusu.sivan.common.enums.TokenSource;
import com.icusu.sivan.core.model.TokenUsage;
import com.icusu.sivan.domain.shared.vo.TokenContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TokenUsageRecorderTest {

    @Mock private com.icusu.sivan.domain.token.TokenUsageRepository repository;
    @Captor private ArgumentCaptor<com.icusu.sivan.domain.token.TokenUsage> usageCaptor;

    private TokenUsageRecorder recorder;
    private final TokenContext ctx = TokenContext.builder()
            .accountId(UUID.randomUUID()).source(TokenSource.CHAT).build();
    private final String modelName = "gpt-4o";

    @BeforeEach
    void setUp() {
        recorder = new TokenUsageRecorder(repository);
    }

    @Test
    void saveUsage_shouldSave_whenUsageValid() {
        TokenUsage usage = new TokenUsage(100, 50, 150);

        recorder.saveUsage(usage, ctx, modelName);

        verify(repository).save(usageCaptor.capture());
        assertEquals(100, usageCaptor.getValue().getInputTokens());
        assertEquals(50, usageCaptor.getValue().getOutputTokens());
        assertEquals(modelName, usageCaptor.getValue().getModelName());
    }

    @Test
    void saveUsage_shouldSetDefaultModelName_whenNull() {
        TokenUsage usage = new TokenUsage(100, 50, 150);

        recorder.saveUsage(usage, ctx, null);

        verify(repository).save(usageCaptor.capture());
        assertEquals("", usageCaptor.getValue().getModelName());
    }

    @Test
    void saveUsage_shouldDoNothing_whenUsageNull() {
        recorder.saveUsage((TokenUsage) null, ctx, modelName);
        verifyNoInteractions(repository);
    }

    @Test
    void saveUsage_shouldDoNothing_whenCtxNull() {
        TokenUsage usage = new TokenUsage(100, 50, 150);
        recorder.saveUsage(usage, null, modelName);
        verifyNoInteractions(repository);
    }

    @Test
    void saveUsage_shouldDoNothing_whenTotalTokensZero() {
        TokenUsage usage = new TokenUsage(0, 0, 0);
        recorder.saveUsage(usage, ctx, modelName);
        verifyNoInteractions(repository);
    }

    @Test
    void saveUsage_shouldCatchException() {
        doThrow(new RuntimeException("DB 异常")).when(repository).save(any());
        TokenUsage usage = new TokenUsage(100, 50, 150);

        assertDoesNotThrow(() -> recorder.saveUsage(usage, ctx, modelName));
    }

    @Test
    void saveUsage_overload_shouldDelegate() {
        TokenUsage usage = new TokenUsage(100, 50, 150);

        recorder.saveUsage(usage, ctx);

        verify(repository).save(any());
    }

    @Test
    void saveUsage_withAgentResult_shouldSave() {
        AgentResult result = new AgentResult("ok", "", "gpt-4o", 150, 100, 50);

        recorder.saveUsage(result, ctx, modelName);

        verify(repository).save(usageCaptor.capture());
        assertEquals(100, usageCaptor.getValue().getInputTokens());
        assertEquals(50, usageCaptor.getValue().getOutputTokens());
    }

    @Test
    void saveUsage_withAgentResult_shouldUseResultModelName_whenNull() {
        AgentResult result = new AgentResult("ok", "", "claude-3", 150, 100, 50);

        recorder.saveUsage(result, ctx, null);

        verify(repository).save(usageCaptor.capture());
        assertEquals("claude-3", usageCaptor.getValue().getModelName());
    }

    @Test
    void saveUsage_withAgentResult_shouldDoNothing_whenTotalZero() {
        AgentResult result = new AgentResult("ok", "", "gpt-4o", 0, 0, 0);

        recorder.saveUsage(result, ctx, modelName);

        verifyNoInteractions(repository);
    }

    @Test
    void saveUsage_withAgentResult_shouldDoNothing_whenResultNull() {
        recorder.saveUsage((AgentResult) null, ctx, modelName);
        verifyNoInteractions(repository);
    }
}
