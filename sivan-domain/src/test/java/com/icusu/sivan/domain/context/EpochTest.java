package com.icusu.sivan.domain.context;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EpochTest {

    @Test
    void fromIndex_returnsCorrect() {
        assertEquals(Epoch.EPOCH_0_SYSTEM, Epoch.fromIndex(0));
        assertEquals(Epoch.EPOCH_1_PROFILE, Epoch.fromIndex(1));
        assertEquals(Epoch.EPOCH_2_COMPLETED, Epoch.fromIndex(2));
        assertEquals(Epoch.EPOCH_3_HISTORY, Epoch.fromIndex(3));
        assertEquals(Epoch.EPOCH_4_ACTIVE, Epoch.fromIndex(4));
    }

    @Test
    void fromIndex_invalid_returnsActive() {
        assertEquals(Epoch.EPOCH_4_ACTIVE, Epoch.fromIndex(999));
        assertEquals(Epoch.EPOCH_4_ACTIVE, Epoch.fromIndex(-1));
    }

    @Test
    void isCacheable_systemProfileCompleted() {
        assertTrue(Epoch.EPOCH_0_SYSTEM.isCacheable());
        assertTrue(Epoch.EPOCH_1_PROFILE.isCacheable());
        assertTrue(Epoch.EPOCH_2_COMPLETED.isCacheable());
    }

    @Test
    void isCacheable_historyAndActiveNot() {
        assertFalse(Epoch.EPOCH_3_HISTORY.isCacheable());
        assertFalse(Epoch.EPOCH_4_ACTIVE.isCacheable());
    }

    @Test
    void getIndex_isConsistent() {
        assertEquals(0, Epoch.EPOCH_0_SYSTEM.getIndex());
        assertEquals(4, Epoch.EPOCH_4_ACTIVE.getIndex());
    }

    @Test
    void allValues_count() {
        assertEquals(5, Epoch.values().length);
    }
}
