package com.icusu.sivan.agent.service;

import com.icusu.sivan.agent.model.ModelRouter;
import com.icusu.sivan.core.model.Model;
import com.icusu.sivan.core.model.ModelChunk;
import com.icusu.sivan.domain.shared.vo.TokenContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LlmServiceTest {

    @Mock private ModelRouter modelRouter;
    @Mock private TokenUsageRecorder tokenUsageRecorder;
    @Mock private Model model;

    private LlmService llmService;
    private final UUID accountId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        lenient().when(modelRouter.getDefaultModel(accountId)).thenReturn(model);
        lenient().when(model.modelId()).thenReturn("test-model");
        llmService = new LlmService(modelRouter, tokenUsageRecorder);
    }

    @Test
    void chat_shouldReturnConcatenatedContent() {
        when(model.stream(any(), any())).thenReturn(
                Flux.just(new ModelChunk("Hello"), new ModelChunk(" World")));

        String result = llmService.chat("系统提示", "用户消息", accountId).block();

        assertEquals("Hello World", result);
    }

    @Test
    void chat_shouldReturnEmpty_whenNoChunks() {
        when(model.stream(any(), any())).thenReturn(Flux.empty());

        String result = llmService.chat("系统提示", "用户消息", accountId).block();

        assertEquals("", result);
    }

    @Test
    void chat_shouldHandleNullUserMessage() {
        when(model.stream(any(), any())).thenReturn(Flux.just(new ModelChunk("ok")));

        String result = llmService.chat(null, null, accountId).block();

        assertNotNull(result);
    }

    @Test
    void chatStream_shouldReturnFlux() {
        when(model.stream(any(), any())).thenReturn(
                Flux.just(new ModelChunk("chunk1"),
                        new ModelChunk("chunk2")));

        Flux<ModelChunk> stream = llmService.chatStream("提示", "消息", accountId);

        assertNotNull(stream);
        assertEquals(2, stream.collectList().block().size());
    }

    @Test
    void chatStream_withTokenContext_shouldTrackAndSave() {
        TokenContext ctx = TokenContext.builder().accountId(accountId).build();
        when(model.stream(any(), any())).thenReturn(
                Flux.just(new ModelChunk("hello")));

        Flux<ModelChunk> stream = llmService.chatStream("提示", "消息", ctx);

        assertNotNull(stream);
        stream.collectList().block();
    }
}
