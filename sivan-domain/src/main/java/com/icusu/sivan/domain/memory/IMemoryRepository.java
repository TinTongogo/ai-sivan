package com.icusu.sivan.domain.memory;

import com.icusu.sivan.common.enums.MemoryLevel;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 记忆仓储接口，对应四层认知模型。
 */
public interface IMemoryRepository {

    /** 保存记忆条目。 */
    UUID save(MemoryEntry entry);

    /** 更新记忆条目。 */
    void update(MemoryEntry entry);

    /** 根据 ID 和用户查找记忆条目。 */
    Optional<MemoryEntry> findByIdAndAccount(UUID memoryId, UUID accountId);

    /** 根据层级和作用域查找记忆条目。 */
    List<MemoryEntry> findByLevelAndScope(UUID accountId, MemoryLevel level, String scopeId);

    /** 根据层级、作用域和项目查找记忆条目。 */
    List<MemoryEntry> findByLevelAndScopeAndProject(UUID accountId, MemoryLevel level, String scopeId, UUID projectId);

    /** 获取指定用户的所有记忆条目。 */
    List<MemoryEntry> findAllByAccount(UUID accountId);

    /** 分页获取指定用户的所有记忆条目。 */
    List<MemoryEntry> findAllByAccountPage(UUID accountId, int page, int size);

    /** 分页获取指定用户的记忆条目（按层级筛选）。 */
    List<MemoryEntry> findAllByAccountPage(UUID accountId, String level, int page, int size);

    /** 按关键词分页搜索记忆条目。 */
    List<MemoryEntry> searchByKeyword(UUID accountId, String keyword, int page, int size);

    /** 统计关键词匹配的记忆条目数。 */
    long countByKeyword(UUID accountId, String keyword);

    /** 查找指定层级且未归档的记忆条目（用于长期记忆注入）。 */
    List<MemoryEntry> findByLevel(UUID accountId, MemoryLevel level);

    /** 查找可归档的记忆条目。 */
    List<MemoryEntry> findArchivable(UUID accountId, float threshold);

    /** 查找所有未归档的记忆条目（跨账户，供调度任务使用）。 */
    List<MemoryEntry> findAllNonArchived();

    /** 查找重要的记忆条目。 */
    List<MemoryEntry> findImportant(UUID accountId, UUID projectId, int limit);

    /** 语义搜索记忆条目。 */
    List<MemoryEntry> semanticSearch(UUID accountId, MemoryLevel level, String scopeId, float[] queryVector, int topK);

    /** 删除记忆条目。 */
    void delete(UUID memoryId);

    /** 批量删除记录。 */
    void deleteBatch(java.util.List<UUID> ids);

    /** 统计指定用户的记忆条目总数。 */
    long countByAccount(UUID accountId);

    /** 统计指定用户的记忆条目总数（按层级筛选）。 */
    long countByAccount(UUID accountId, String level);

    /** 统计指定用户的重要记忆条目数。 */
    long countImportantByAccount(UUID accountId);

    /** 统计指定用户的已归档记忆条目数。 */
    long countArchivedByAccount(UUID accountId);
}
