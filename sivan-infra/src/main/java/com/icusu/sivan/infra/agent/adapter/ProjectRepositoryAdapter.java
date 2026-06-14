package com.icusu.sivan.infra.agent.adapter;

import com.icusu.sivan.domain.agent.Project;
import com.icusu.sivan.domain.agent.repository.IProjectRepository;
import com.icusu.sivan.infra.agent.entity.ProjectEntity;
import com.icusu.sivan.infra.agent.repository.ProjectJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 项目仓储适配器 — 将领域 Project 映射为 JPA ProjectEntity。
 */
@Repository
@RequiredArgsConstructor
public class ProjectRepositoryAdapter implements IProjectRepository {

    private final ProjectJpaRepository jpaRepository;

    @Override
    public List<Project> findByAccountIdOrderBySortOrderAsc(UUID accountId) {
        return jpaRepository.findByAccountIdOrderBySortOrderAsc(accountId).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public long countByAccountId(UUID accountId) {
        return jpaRepository.countByAccountId(accountId);
    }

    @Override
    public Optional<Project> findById(UUID projectId) {
        return jpaRepository.findById(projectId).map(this::toDomain);
    }

    @Override
    public Project save(Project project) {
        ProjectEntity entity = toEntity(project);
        ProjectEntity saved = jpaRepository.save(entity);
        return toDomain(saved);
    }

    @Override
    public void delete(Project project) {
        jpaRepository.deleteById(project.getProjectId());
    }

    @Override
    public boolean existsByAccountIdAndShortId(UUID accountId, String shortId) {
        return jpaRepository.existsByAccountIdAndShortId(accountId, shortId);
    }

    @Override
    public List<Project> findAllByAccountId(UUID accountId) {
        return jpaRepository.findByAccountIdOrderBySortOrderAsc(accountId).stream()
                .map(this::toDomain)
                .toList();
    }

    private Project toDomain(ProjectEntity entity) {
        return Project.builder()
                .projectId(entity.getProjectId())
                .accountId(entity.getAccountId())
                .name(entity.getName())
                .description(entity.getDescription())
                .sortOrder(entity.getSortOrder())
                .localPath(entity.getLocalPath())
                .undeletable(entity.getUndeletable())
                .archived(entity.getArchived())
                .archivedAt(entity.getArchivedAt())
                .localPathAuto(entity.getLocalPathAuto())
                .shortId(entity.getShortId())
                .build();
    }

    private ProjectEntity toEntity(Project domain) {
        return ProjectEntity.builder()
                .projectId(domain.getProjectId())
                .accountId(domain.getAccountId())
                .name(domain.getName())
                .description(domain.getDescription())
                .sortOrder(domain.getSortOrder())
                .localPath(domain.getLocalPath())
                .undeletable(domain.getUndeletable())
                .archived(domain.getArchived())
                .archivedAt(domain.getArchivedAt())
                .localPathAuto(domain.getLocalPathAuto())
                .shortId(domain.getShortId())
                .build();
    }
}
