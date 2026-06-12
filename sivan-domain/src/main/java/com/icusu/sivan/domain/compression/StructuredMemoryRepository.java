package com.icusu.sivan.domain.compression;

import java.util.List;
import java.util.UUID;

/**
 * 结构化记忆仓储接口。
 */
public interface StructuredMemoryRepository {

    void save(StructuredMemory memory);

    void saveAll(List<StructuredMemory> memories);

    List<StructuredMemory> findByAccount(UUID accountId);

    List<StructuredMemory> findByType(UUID accountId, StructuredMemory.Type type);

    void delete(UUID memoryId);
}
