package com.icusu.sivan.application.conversation.service.tree;

import com.icusu.sivan.application.conversation.tree.ContextResult;
import com.icusu.sivan.domain.context.ContextSegment;
import com.icusu.sivan.domain.context.Epoch;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ContextResultTest {

    @Test
    void empty_returnsEmpty() {
        assertTrue(ContextResult.empty().isEmpty());
        assertEquals("", ContextResult.empty().toFlatString());
        assertEquals(0, ContextResult.empty().size());
    }

    @Test
    void toFlatString_combinesSegments() {
        ContextResult result = new ContextResult(List.of(
                new ContextSegment(Epoch.EPOCH_3_HISTORY, "历史摘要", false),
                new ContextSegment(Epoch.EPOCH_4_ACTIVE, "活跃消息", false)
        ));
        String flat = result.toFlatString();
        assertTrue(flat.contains("历史摘要"));
        assertTrue(flat.contains("活跃消息"));
    }

    @Test
    void getContent_returnsCorrectEpoch() {
        ContextResult result = new ContextResult(List.of(
                new ContextSegment(Epoch.EPOCH_3_HISTORY, "历史", false),
                new ContextSegment(Epoch.EPOCH_4_ACTIVE, "活跃", false)
        ));
        assertEquals("历史", result.getContent(Epoch.EPOCH_3_HISTORY));
        assertEquals("活跃", result.getContent(Epoch.EPOCH_4_ACTIVE));
    }

    @Test
    void getContent_missingEpoch_returnsEmpty() {
        ContextResult result = new ContextResult(List.of(
                new ContextSegment(Epoch.EPOCH_3_HISTORY, "历史", false)
        ));
        assertEquals("", result.getContent(Epoch.EPOCH_1_PROFILE));
    }

    @Test
    void getCachedEpochIndices_returnsOnlyCached() {
        ContextResult result = new ContextResult(List.of(
                new ContextSegment(Epoch.EPOCH_0_SYSTEM, "", true),
                new ContextSegment(Epoch.EPOCH_1_PROFILE, "画像", true),
                new ContextSegment(Epoch.EPOCH_3_HISTORY, "摘要", false)
        ));
        List<Integer> cached = result.getCachedEpochIndices();
        assertTrue(cached.contains(0));
        assertTrue(cached.contains(1));
        assertFalse(cached.contains(3));
    }

    @Test
    void isEmpty_noContent() {
        ContextResult result = new ContextResult(List.of(
                new ContextSegment(Epoch.EPOCH_0_SYSTEM, "", false),
                new ContextSegment(Epoch.EPOCH_1_PROFILE, "", false)
        ));
        assertTrue(result.isEmpty());
    }

    @Test
    void nullSegments_handledGracefully() {
        ContextResult result = new ContextResult(null);
        assertTrue(result.isEmpty());
        assertEquals(0, result.size());
    }
}
