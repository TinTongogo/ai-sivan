package com.icusu.sivan.infra.adapter;

import com.icusu.sivan.common.enums.MemoryLevel;
import com.icusu.sivan.domain.memory.IMemoryRepository;
import com.icusu.sivan.domain.memory.MemoryEntry;
import com.icusu.sivan.infra.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Sql("/disable-fk.sql")
@Transactional
class MemoryRepositoryAdapterTest extends AbstractIntegrationTest {

    @Autowired
    private IMemoryRepository repository;

    @Test
    void shouldSaveAndFindById() {
        UUID accountId = UUID.randomUUID();
        MemoryEntry entry = MemoryEntry.builder()
                .accountId(accountId)
                .level(MemoryLevel.SESSION)
                .scopeId("default")
                .content("测试记忆内容")
                .summary("测试摘要")
                .build();
        repository.save(entry);

        assertNotNull(entry.getMemoryId());

        MemoryEntry found = repository.findByIdAndAccount(entry.getMemoryId(), accountId).orElse(null);
        assertNotNull(found);
        assertEquals("测试记忆内容", found.getContent());
        assertEquals(MemoryLevel.SESSION, found.getLevel());
    }

    @Test
    void shouldDelete() {
        UUID accountId = UUID.randomUUID();
        MemoryEntry entry = MemoryEntry.builder()
                .accountId(accountId).level(MemoryLevel.SESSION).scopeId("default")
                .content("待删除").build();
        repository.save(entry);
        UUID id = entry.getMemoryId();

        repository.delete(id);
        assertTrue(repository.findByIdAndAccount(id, accountId).isEmpty());
    }
}
