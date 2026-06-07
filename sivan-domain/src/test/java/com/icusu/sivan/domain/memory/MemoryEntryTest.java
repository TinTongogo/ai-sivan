package com.icusu.sivan.domain.memory;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MemoryEntryTest {

    @Test
    void archive_shouldSetArchived() {
        MemoryEntry e = entry();
        e.archive();
        assertTrue(e.isArchived());
    }

    @Test
    void isArchived_shouldReturnFalse_whenNotArchived() {
        MemoryEntry e = entry();
        assertFalse(e.isArchived());
    }

    @Test
    void isArchived_shouldReturnFalse_whenNull() {
        MemoryEntry e = entry();
        e.setArchived(null);
        assertFalse(e.isArchived());
    }

    @Test
    void access_shouldIncrementCountAndSetTimestamp() {
        MemoryEntry e = entry();
        e.setAccessCount(3);
        e.access();
        assertEquals(4, e.getAccessCount());
        assertNotNull(e.getLastAccessedAt());
    }

    @Test
    void access_shouldHandleNullAccessCount() {
        MemoryEntry e = entry();
        e.setAccessCount(null);
        e.access();
        assertEquals(1, e.getAccessCount());
    }

    @Test
    void decay_shouldReduceRetention() {
        MemoryEntry e = entry();
        e.setRetention(1.0f);
        e.decay(0.5f);
        assertEquals(0.5f, e.getRetention(), 0.001);
    }

    @Test
    void decay_shouldClampAtZero() {
        MemoryEntry e = entry();
        e.setRetention(0.1f);
        e.decay(0.0f);
        assertEquals(0.0f, e.getRetention(), 0.001);
    }

    @Test
    void decay_shouldHandleNullRetention() {
        MemoryEntry e = entry();
        e.setRetention(null);
        e.decay(0.5f);
        assertEquals(0.5f, e.getRetention(), 0.001);
    }

    @Test
    void markImportant_shouldSetFlag() {
        MemoryEntry e = entry();
        e.markImportant();
        assertTrue(e.getImportant());
    }

    @Test
    void updateFrom_shouldUpdateNonNullFields() {
        MemoryEntry e = entry();
        e.setContent("old");
        e.setSummary("old summary");
        e.updateFrom("new content", null, true, 0.5f, null);
        assertEquals("new content", e.getContent());
        assertEquals("old summary", e.getSummary()); // not updated
        assertTrue(e.getImportant());
        assertEquals(0.5f, e.getRetention(), 0.001);
    }

    @Test
    void updateFrom_shouldNotUpdateNullFields() {
        MemoryEntry e = entry();
        e.setContent("original");
        e.updateFrom(null, null, null, null, null);
        assertEquals("original", e.getContent());
    }

    private static MemoryEntry entry() {
        return MemoryEntry.builder()
                .memoryId(UUID.randomUUID())
                .accountId(UUID.randomUUID())
                .content("test memory")
                .build();
    }
}
