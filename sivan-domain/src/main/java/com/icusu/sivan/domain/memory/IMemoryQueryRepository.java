package com.icusu.sivan.domain.memory;

import com.icusu.sivan.common.enums.MemoryLevel;

import java.util.List;
import java.util.UUID;

/**
 * 记忆条目查询与搜索仓储接口。
 */
public interface IMemoryQueryRepository {

    List<MemoryEntry> findByLevelAndScope(UUID accountId, MemoryLevel level, String scopeId);

    List<MemoryEntry> findByLevelAndScopeAndProject(UUID accountId, MemoryLevel level, String scopeId, UUID projectId);

    List<MemoryEntry> findAllByAccount(UUID accountId);

    List<MemoryEntry> findAllByAccountPage(UUID accountId, int page, int size);

    List<MemoryEntry> findAllByAccountPage(UUID accountId, String level, int page, int size);

    List<MemoryEntry> findByLevel(UUID accountId, MemoryLevel level);

    List<MemoryEntry> findArchivable(UUID accountId, float threshold);

    List<MemoryEntry> findAllNonArchived();

    List<MemoryEntry> findImportant(UUID accountId, UUID projectId, int limit);

    List<MemoryEntry> semanticSearch(UUID accountId, MemoryLevel level, String scopeId, float[] queryVector, int topK);

    long countByAccount(UUID accountId);

    long countByAccount(UUID accountId, String level);

    long countImportantByAccount(UUID accountId);

    long countArchivedByAccount(UUID accountId);
}
