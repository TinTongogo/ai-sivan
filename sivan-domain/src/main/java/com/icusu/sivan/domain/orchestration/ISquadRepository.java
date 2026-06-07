package com.icusu.sivan.domain.orchestration;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Squad 编排模板仓储接口。
 */
public interface ISquadRepository {

    /** 根据 ID 查找 Squad 模板。 */
    Optional<Squad> findById(UUID squadId);

    /** 获取指定用户的所有 Squad 模板。 */
    List<Squad> findAllByAccount(UUID accountId);

    /** 获取指定用户的所有已启用 Squad 模板。 */
    List<Squad> findAllByAccountAndActiveTrue(UUID accountId);

    /** 分页获取指定用户的所有 Squad 模板。 */
    List<Squad> findAllByAccountPage(UUID accountId, int page, int size);

    /** 获取指定用户和项目的 Squad 模板列表。 */
    List<Squad> findAllByAccountAndProject(UUID accountId, UUID projectId);

    /** 分页获取指定用户和项目的 Squad 模板列表。 */
    List<Squad> findAllByAccountAndProjectPage(UUID accountId, UUID projectId, int page, int size);

    /** 保存 Squad 模板。 */
    void save(Squad squad);

    /** 更新 Squad 模板。 */
    void update(Squad squad);

    /** 删除 Squad 模板。 */
    void delete(UUID squadId);

    /** 批量删除记录。 */
    void deleteBatch(java.util.List<UUID> ids);

    /**
     * 统计指定用户下的 Squad 总数。
     */
    long countByAccount(UUID accountId);
}
