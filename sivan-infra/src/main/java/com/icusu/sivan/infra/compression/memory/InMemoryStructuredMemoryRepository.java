package com.icusu.sivan.infra.compression.memory;

import com.icusu.sivan.domain.memory.StructuredMemory;
import com.icusu.sivan.domain.memory.repository.StructuredMemoryRepository;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存结构化记忆仓储 — Phase 0 实现，重启后丢失。
 */
@Component
public class InMemoryStructuredMemoryRepository implements StructuredMemoryRepository {

    private final Map<UUID, StructuredMemory> store = new ConcurrentHashMap<>();

    @Override
    public void save(StructuredMemory memory) {
        store.put(memory.getMemoryId(), memory);
    }

    @Override
    public void saveAll(List<StructuredMemory> memories) {
        for (StructuredMemory m : memories) {
            store.put(m.getMemoryId(), m);
        }
    }

    @Override
    public List<StructuredMemory> findByAccount(UUID accountId) {
        return store.values().stream()
                .filter(m -> accountId.equals(m.getAccountId()))
                .sorted(Comparator.comparing(StructuredMemory::getCreatedAt).reversed())
                .toList();
    }

    @Override
    public List<StructuredMemory> findByType(UUID accountId, StructuredMemory.Type type) {
        return store.values().stream()
                .filter(m -> accountId.equals(m.getAccountId()))
                .filter(m -> type == m.getType())
                .toList();
    }

    @Override
    public void delete(UUID memoryId) {
        store.remove(memoryId);
    }
}
