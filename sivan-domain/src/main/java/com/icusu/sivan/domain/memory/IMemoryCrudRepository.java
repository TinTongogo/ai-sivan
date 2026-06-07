package com.icusu.sivan.domain.memory;

import java.util.Optional;
import java.util.UUID;

/**
 * 记忆条目 CRUD 仓储接口。
 */
public interface IMemoryCrudRepository {

    UUID save(MemoryEntry entry);

    void update(MemoryEntry entry);

    Optional<MemoryEntry> findByIdAndAccount(UUID memoryId, UUID accountId);

    void delete(UUID memoryId);
}
