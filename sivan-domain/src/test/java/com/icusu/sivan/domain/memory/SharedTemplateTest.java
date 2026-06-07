package com.icusu.sivan.domain.memory;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SharedTemplate 领域实体单元测试。
 */
class SharedTemplateTest {

    @Test
    void shouldCreatePublicTemplate() {
        SharedTemplate t = SharedTemplate.builder()
                .patternId(UUID.randomUUID())
                .ownerAccountId(UUID.randomUUID())
                .visibility(SharedTemplate.Visibility.PUBLIC)
                .build();

        assertNull(t.getTemplateId()); // null before save
        assertEquals(SharedTemplate.Visibility.PUBLIC, t.getVisibility());
        assertTrue(t.isActive());
        assertEquals(0, t.getUseCount());
    }

    @Test
    void shouldCreateListTemplate() {
        UUID allowed = UUID.randomUUID();
        SharedTemplate t = SharedTemplate.builder()
                .patternId(UUID.randomUUID())
                .ownerAccountId(UUID.randomUUID())
                .visibility(SharedTemplate.Visibility.LIST)
                .allowedAccounts("[\"" + allowed + "\"]")
                .build();

        assertEquals(SharedTemplate.Visibility.LIST, t.getVisibility());
        assertTrue(t.getAllowedAccounts().contains(allowed.toString()));
    }

    @Test
    void defaultStatusShouldBeActive() {
        SharedTemplate t = SharedTemplate.builder()
                .patternId(UUID.randomUUID())
                .ownerAccountId(UUID.randomUUID())
                .visibility(SharedTemplate.Visibility.PUBLIC)
                .build();

        assertTrue(t.isActive());
        assertFalse(t.isOrphaned());
    }

    @Test
    void recordUsageShouldIncrement() {
        SharedTemplate t = template();
        assertEquals(0, t.getUseCount());
        t.recordUsage();
        assertEquals(1, t.getUseCount());
        t.recordUsage();
        assertEquals(2, t.getUseCount());
    }

    @Test
    void recordSuccessShouldIncrement() {
        SharedTemplate t = template();
        assertEquals(0, t.getSuccessCount());
        t.recordSuccess();
        assertEquals(1, t.getSuccessCount());
    }

    @Test
    void markOrphanedShouldSetStatus() {
        SharedTemplate t = template();
        assertTrue(t.isActive());
        t.markOrphaned();
        assertFalse(t.isActive());
        assertTrue(t.isOrphaned());
    }

    private SharedTemplate template() {
        return SharedTemplate.builder()
                .patternId(UUID.randomUUID())
                .ownerAccountId(UUID.randomUUID())
                .visibility(SharedTemplate.Visibility.PUBLIC)
                .build();
    }
}
