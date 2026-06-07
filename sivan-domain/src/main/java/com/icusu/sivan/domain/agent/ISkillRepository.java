package com.icusu.sivan.domain.agent;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 技能仓储接口。
 */
public interface ISkillRepository {

    /** 根据 ID 查找技能。 */
    Optional<Skill> findById(UUID skillId);

    /** 根据用户和编码查找技能。 */
    Optional<Skill> findByAccountAndCode(UUID accountId, String skillCode);

    /** 根据用户和名称查找技能。 */
    Optional<Skill> findByAccountAndName(UUID accountId, String name);

    /** 根据用户、项目和名称查找技能。 */
    Optional<Skill> findByAccountAndProjectAndName(UUID accountId, UUID projectId, String name);

    /** 获取指定用户的所有技能。 */
    List<Skill> findAllByAccount(UUID accountId);

    /** 获取指定用户的活跃技能。 */
    List<Skill> findAllActiveByAccount(UUID accountId);

    /** 获取指定用户和项目的技能列表。 */
    List<Skill> findAllByAccountAndProject(UUID accountId, UUID projectId);

    /** 获取指定用户和分类的技能列表。 */
    List<Skill> findAllByAccountAndCategory(UUID accountId, String category);

    /** 保存技能。 */
    void save(Skill skill);

    /** 删除技能。 */
    void delete(UUID skillId);

    /** 批量删除记录。 */
    void deleteBatch(java.util.List<UUID> ids);

    /** 检查技能编码是否已被其他技能使用。 */
    boolean existsByCodeExcludingId(UUID accountId, String skillCode, UUID excludeId);

    /**
     * 统计指定用户下的技能总数。
     */
    long countByAccount(UUID accountId);
}
