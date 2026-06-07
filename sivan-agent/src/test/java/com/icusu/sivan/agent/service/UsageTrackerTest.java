package com.icusu.sivan.agent.service;

import com.icusu.sivan.core.model.Model;
import com.icusu.sivan.core.model.ModelChunk;
import com.icusu.sivan.core.model.TokenUsage;
import com.icusu.sivan.domain.shared.vo.TokenContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UsageTrackerTest {

    @Mock private TokenUsageRecorder recorder;

    private final UsageTracker tracker = new UsageTracker();
    private final TokenContext ctx = TokenContext.builder().build();
    private final TokenUsage usage = new TokenUsage(100, 50, 150);

    @Test
    void initial_shouldBeEmpty() {
        assertNull(tracker.get());
        assertEquals(0, tracker.totalTokens());
        assertEquals(0, tracker.inputTokens());
        assertEquals(0, tracker.outputTokens());
    }

    @Test
    void capture_shouldStoreUsage() {
        ModelChunk chunk = new ModelChunk("hello", null, usage);

        tracker.capture(chunk);

        assertNotNull(tracker.get());
        assertEquals(150, tracker.totalTokens());
        assertEquals(100, tracker.inputTokens());
        assertEquals(50, tracker.outputTokens());
    }

    @Test
    void capture_shouldIgnoreNull() {
        tracker.capture(null);
        assertNull(tracker.get());
    }

    @Test
    void capture_shouldIgnoreChunkWithoutUsage() {
        ModelChunk chunk = new ModelChunk("hello");

        tracker.capture(chunk);
        assertNull(tracker.get());
    }

    @Test
    void captureFromResponse_shouldStoreUsage() {
        Model.ModelResponse response = mock(Model.ModelResponse.class);
        when(response.usage()).thenReturn(usage);

        tracker.captureFromResponse(response);

        assertEquals(150, tracker.totalTokens());
    }

    @Test
    void captureFromResponse_shouldIgnoreNull() {
        tracker.captureFromResponse(null);
        assertNull(tracker.get());
    }

    @Test
    void captureConsumer_shouldWorkWithDoOnNext() {
        AtomicInteger counter = new AtomicInteger(0);
        ModelChunk chunk = new ModelChunk("ok", null, usage);

        tracker.captureConsumer().accept(chunk);

        assertEquals(150, tracker.totalTokens());
    }

    @Test
    void saveIfNeeded_shouldSave_whenUsageExists() {
        tracker.capture(new ModelChunk("ok", null, usage));

        tracker.saveIfNeeded(ctx, "gpt-4o", recorder);

        verify(recorder).saveUsage(usage, ctx, "gpt-4o");
    }

    @Test
    void saveIfNeeded_shouldDoNothing_whenNoUsage() {
        tracker.saveIfNeeded(ctx, "gpt-4o", recorder);
        verifyNoInteractions(recorder);
    }

    @Test
    void saveIfNeeded_shouldDoNothing_whenCtxNull() {
        tracker.capture(new ModelChunk("ok", null, usage));

        tracker.saveIfNeeded(null, "gpt-4o", recorder);

        verifyNoInteractions(recorder);
    }

    @Test
    void reset_shouldClear() {
        tracker.capture(new ModelChunk("ok", null, usage));
        assertNotNull(tracker.get());

        tracker.reset();

        assertNull(tracker.get());
    }
}
