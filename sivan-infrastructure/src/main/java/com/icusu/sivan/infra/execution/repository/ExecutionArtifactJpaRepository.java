package com.icusu.sivan.infra.execution.repository;

import com.icusu.sivan.infra.execution.entity.ExecutionArtifactEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * 执行产物表数据访问接口。
 */
@Repository
public interface ExecutionArtifactJpaRepository extends JpaRepository<ExecutionArtifactEntity, UUID> {

    List<ExecutionArtifactEntity> findByExecutionIdOrderByCreatedAtAsc(UUID executionId);

    List<ExecutionArtifactEntity> findByExecutionIdAndArtifactTypeOrderByCreatedAtAsc(
            UUID executionId, String artifactType);

    void deleteByExecutionId(UUID executionId);
}
