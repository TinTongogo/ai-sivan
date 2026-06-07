package com.icusu.sivan.domain.orchestration;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Squad 执行记录仓储接口。
 */
public interface ISquadExecutionRepository {

    /** 根据 ID 查找执行记录。 */
    Optional<SquadExecution> findById(UUID executionId);

    /** 获取指定用户的执行记录列表。 */
    List<SquadExecution> findByAccount(UUID accountId);

    /** 根据 Squad ID 查找执行记录列表。 */
    List<SquadExecution> findBySquadId(UUID squadId);

    /** 根据 Squad ID 和账号 ID 查找执行记录列表。 */
    List<SquadExecution> findBySquadIdAndAccountId(UUID squadId, UUID accountId);

    /** 分页根据 Squad ID 查找执行记录列表。 */
    List<SquadExecution> findBySquadIdPage(UUID squadId, int page, int size);

    /**
     * 统计指定 Squad 的执行记录总数。
     */
    long countBySquadId(UUID squadId);

    /** 保存执行记录。 */
    void save(SquadExecution execution);

    /** 更新执行状态。 */
    void updateStatus(UUID executionId, String status, String errorMessage);

    /** 更新当前执行阶段。 */
    void updateCurrentPhase(UUID executionId, int currentPhase);

    /** 更新 Agent 状态快照（JSONB，用于 HITL 断点续跑）。 */
    void updateAgentState(UUID executionId, String agentStateJson);

    /** 更新执行结果（content/thinking）。 */
    void updateResult(UUID executionId, String content, String thinking);

    /**
     * 统计指定用户下的执行记录总数。
     */
    long countByAccount(UUID accountId);

    /**
     * 获取指定用户最近的 N 条执行记录。
     */
    List<SquadExecution> findRecentByAccount(UUID accountId, int limit);

    /** 删除单条执行记录。 */
    void delete(UUID executionId);

    /** 批量删除记录。 */
    void deleteBatch(java.util.List<UUID> ids);

    /** 删除 Squad 下的所有执行记录。 */
    void deleteBySquadId(UUID squadId);

    /**
     * 分页按条件查询当前用户的执行记录，支持按状态和 Squad 过滤。
     */
    List<SquadExecution> findByAccountPage(UUID accountId, int page, int size, String status, UUID squadId);

    /**
     * 统计当前用户按状态的执行记录数。
     */
    long countByAccountAndStatus(UUID accountId, String status);

    /**
     * 统计当前用户某时间之后特定状态的执行记录数。
     */
    long countByAccountAndStatusSince(UUID accountId, String status, java.time.LocalDateTime since);
}
