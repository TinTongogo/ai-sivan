package com.icusu.sivan.domain.context;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ContextSegmentTest {

    @Test
    void hasContent_returnsTrue() {
        ContextSegment seg = new ContextSegment(Epoch.EPOCH_3_HISTORY, "摘要内容", false);
        assertTrue(seg.hasContent());
    }

    @Test
    void hasContent_empty_returnsFalse() {
        ContextSegment seg = new ContextSegment(Epoch.EPOCH_0_SYSTEM, "", false);
        assertFalse(seg.hasContent());
    }

    @Test
    void hasContent_blank_returnsFalse() {
        ContextSegment seg = new ContextSegment(Epoch.EPOCH_1_PROFILE, "   ", false);
        assertFalse(seg.hasContent());
    }

    @Test
    void constructor_setsFields() {
        ContextSegment seg = new ContextSegment(Epoch.EPOCH_2_COMPLETED, "completed", true);
        assertEquals(Epoch.EPOCH_2_COMPLETED, seg.getEpoch());
        assertEquals("completed", seg.getContent());
        assertTrue(seg.isCacheBreakpoint());
    }

    @Test
    void cacheBreakpoint_false() {
        ContextSegment seg = new ContextSegment(Epoch.EPOCH_4_ACTIVE, "active", false);
        assertFalse(seg.isCacheBreakpoint());
    }
}
