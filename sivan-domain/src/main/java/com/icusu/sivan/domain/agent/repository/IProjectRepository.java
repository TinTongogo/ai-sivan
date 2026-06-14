package com.icusu.sivan.domain.agent.repository;

import com.icusu.sivan.domain.agent.Project;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 项目仓储端口 — 领域层定义，基础设施层实现。
 */
public interface IProjectRepository {

    List<Project> findByAccountIdOrderBySortOrderAsc(UUID accountId);

    long countByAccountId(UUID accountId);

    Optional<Project> findById(UUID projectId);

    Project save(Project project);

    void delete(Project project);

    boolean existsByAccountIdAndShortId(UUID accountId, String shortId);

    List<Project> findAllByAccountId(UUID accountId);
}
