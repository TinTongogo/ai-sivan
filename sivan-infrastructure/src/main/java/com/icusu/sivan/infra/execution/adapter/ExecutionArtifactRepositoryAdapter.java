package com.icusu.sivan.infra.execution.adapter;

import com.icusu.sivan.domain.orchestration.ArtifactType;
import com.icusu.sivan.domain.orchestration.ExecutionArtifact;
import com.icusu.sivan.domain.orchestration.IExecutionArtifactRepository;
import com.icusu.sivan.infra.execution.entity.ExecutionArtifactEntity;
import com.icusu.sivan.infra.execution.repository.ExecutionArtifactJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * 执行产物仓储适配器，实现 IExecutionArtifactRepository。
 */
@Component
@RequiredArgsConstructor
public class ExecutionArtifactRepositoryAdapter implements IExecutionArtifactRepository {

    private final ExecutionArtifactJpaRepository jpaRepository;

    @Override
    public void save(ExecutionArtifact artifact) {
        ExecutionArtifactEntity entity = toEntity(artifact);
        jpaRepository.save(entity);
    }

    @Override
    public List<ExecutionArtifact> findByExecutionId(UUID executionId) {
        return jpaRepository.findByExecutionIdOrderByCreatedAtAsc(executionId).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public List<ExecutionArtifact> findByExecutionIdAndType(UUID executionId, ArtifactType type) {
        return jpaRepository.findByExecutionIdAndArtifactTypeOrderByCreatedAtAsc(
                executionId, type.name()).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public void deleteByExecutionId(UUID executionId) {
        jpaRepository.deleteByExecutionId(executionId);
    }

    // ---- 转换方法 ----

    private ExecutionArtifact toDomain(ExecutionArtifactEntity entity) {
        return new ExecutionArtifact(
                entity.getExecutionId(),
                entity.getPhaseIndex(),
                entity.getName(),
                entity.getContent(),
                ArtifactType.valueOf(entity.getArtifactType())
        ) {
            // 覆盖默认随机生成的 artifactId 以保持与 DB 一致
            @Override
            public UUID getArtifactId() {
                return entity.getArtifactId();
            }

            @Override
            public String getDescription() {
                return entity.getDescription();
            }

            @Override
            public java.time.LocalDateTime getCreatedAt() {
                return entity.getCreatedAt() != null
                        ? entity.getCreatedAt().toLocalDateTime() : null;
            }
        };
    }

    private ExecutionArtifactEntity toEntity(ExecutionArtifact domain) {
        return ExecutionArtifactEntity.builder()
                .executionId(domain.getExecutionId())
                .phaseIndex(domain.getPhaseIndex())
                .artifactType(domain.getArtifactType().name())
                .name(domain.getName())
                .description(domain.getDescription())
                .content(domain.getContent())
                .build();
    }
}
