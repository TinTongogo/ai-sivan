package com.icusu.sivan.infra.agent.repository;

import com.icusu.sivan.infra.agent.entity.SkillEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 技能表数据访问接口。
 */
@Repository
public interface SkillJpaRepository extends JpaRepository<SkillEntity, UUID> {

    Optional<SkillEntity> findByAccountIdAndSkillCode(UUID accountId, String skillCode);

    Optional<SkillEntity> findByAccountIdAndName(UUID accountId, String name);

    Optional<SkillEntity> findByAccountIdAndProjectIdAndName(UUID accountId, UUID projectId, String name);

    List<SkillEntity> findByAccountId(UUID accountId);

    List<SkillEntity> findByAccountIdAndStatus(UUID accountId, String status);

    List<SkillEntity> findByAccountIdAndProjectId(UUID accountId, UUID projectId);

    List<SkillEntity> findByAccountIdAndCategory(UUID accountId, String category);

    boolean existsByAccountIdAndSkillCodeAndSkillIdNot(UUID accountId, String skillCode, UUID excludeId);

    long countByAccountId(UUID accountId);
}
