package com.icusu.sivan.domain.agent;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 智能体仓储接口。
 */
public interface IAgentRepository {

    /**
     * 根据 ID 查找智能体。
     */
    Optional<AgentDefinition> findById(UUID agentId);

    /**
     * 根据名称查找指定用户下的智能体。
     */
    Optional<AgentDefinition> findByAccountAndName(UUID accountId, String agentName);

    /**
     * 获取指定用户的所有智能体。
     */
    List<AgentDefinition> findAllByAccount(UUID accountId);

    /**
     * 保存智能体（新建或更新）。
     */
    void save(AgentDefinition config);

    /**
     * 删除智能体。
     *
     * @throws IllegalStateException 若智能体正在被 Squad 引用
     */
    void delete(UUID agentId);

    /**
     * 批量删除记录。
     */
    void deleteBatch(java.util.List<UUID> ids);

    /**
     * 检查名称是否已被同用户下的其他智能体使用。
     */
    boolean existsByNameExcludingId(UUID accountId, String agentName, UUID excludeId);

    /**
     * 统计各类型的智能体数量。
     */
    Map<String, Long> countByType(UUID accountId);

    /**
     * 统计指定用户下的智能体总数。
     */
    long countByAccount(UUID accountId);
}
