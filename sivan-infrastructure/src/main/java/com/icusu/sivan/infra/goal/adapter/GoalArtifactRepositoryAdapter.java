package com.icusu.sivan.infra.goal.adapter;

import com.icusu.sivan.domain.goal.GoalArtifact;
import com.icusu.sivan.domain.goal.IGoalArtifactRepository;
import com.icusu.sivan.infra.goal.entity.GoalArtifactEntity;
import com.icusu.sivan.infra.goal.repository.GoalArtifactJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * 目标产物仓储适配器，实现 IGoalArtifactRepository。
 */
@Component
@RequiredArgsConstructor
public class GoalArtifactRepositoryAdapter implements IGoalArtifactRepository {

    private final GoalArtifactJpaRepository jpaRepository;

    @Override
    public List<GoalArtifact> findByGoalId(UUID goalId) {
        return jpaRepository.findByGoalId(goalId).stream()
                .map(this::toDomain).toList();
    }

    @Override
    public void save(GoalArtifact artifact) {
        GoalArtifactEntity entity = toEntity(artifact);
        jpaRepository.save(entity);
        if (artifact.getArtifactId() == null) {
            artifact.setArtifactId(entity.getArtifactId());
        }
        artifact.setCreatedAt(entity.getCreatedAt() != null ? entity.getCreatedAt().toLocalDateTime() : null);
    }

    @Override
    public void saveAll(List<GoalArtifact> artifacts) {
        List<GoalArtifactEntity> entities = artifacts.stream().map(this::toEntity).toList();
        List<GoalArtifactEntity> saved = jpaRepository.saveAll(entities);
        for (int i = 0; i < artifacts.size() && i < saved.size(); i++) {
            GoalArtifact domain = artifacts.get(i);
            GoalArtifactEntity entity = saved.get(i);
            if (domain.getArtifactId() == null) {
                domain.setArtifactId(entity.getArtifactId());
            }
            domain.setCreatedAt(entity.getCreatedAt() != null ? entity.getCreatedAt().toLocalDateTime() : null);
        }
    }

    @Override
    public void deleteByGoalId(UUID goalId) {
        jpaRepository.deleteByGoalId(goalId);
    }

    // ========== 转换方法 ==========

    private GoalArtifact toDomain(GoalArtifactEntity entity) {
        return GoalArtifact.builder()
                .artifactId(entity.getArtifactId())
                .goalId(entity.getGoalId())
                .milestoneOrder(entity.getMilestoneOrder())
                .taskOrder(entity.getTaskOrder())
                .filePath(entity.getFilePath())
                .fileType(entity.getFileType())
                .summary(entity.getSummary())
                .fileSize(entity.getFileSize() != null ? entity.getFileSize() : 0L)
                .createdAt(entity.getCreatedAt() != null ? entity.getCreatedAt().toLocalDateTime() : null)
                .build();
    }

    private GoalArtifactEntity toEntity(GoalArtifact domain) {
        return GoalArtifactEntity.builder()
                .artifactId(domain.getArtifactId())
                .goalId(domain.getGoalId())
                .milestoneOrder(domain.getMilestoneOrder())
                .taskOrder(domain.getTaskOrder())
                .filePath(domain.getFilePath())
                .fileType(domain.getFileType())
                .summary(domain.getSummary())
                .fileSize(domain.getFileSize())
                .build();
    }
}
