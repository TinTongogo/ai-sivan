package com.icusu.sivan.infra.agent.repository;

import com.icusu.sivan.infra.agent.entity.ProjectEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 项目表数据访问接口。
 */
@Repository
public interface ProjectJpaRepository extends JpaRepository<ProjectEntity, UUID> {

    List<ProjectEntity> findByAccountIdOrderBySortOrderAsc(UUID accountId);

    Optional<ProjectEntity> findByAccountIdAndName(UUID accountId, String name);

    long countByAccountId(UUID accountId);

    boolean existsByAccountIdAndShortId(UUID accountId, String shortId);

}
