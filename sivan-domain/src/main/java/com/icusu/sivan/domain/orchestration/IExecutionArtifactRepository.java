package com.icusu.sivan.domain.orchestration;

import java.util.List;
import java.util.UUID;

/**
 * 执行产物仓储接口 — 存储阶段输出的结构化产物。
 */
public interface IExecutionArtifactRepository {

    void save(ExecutionArtifact artifact);

    List<ExecutionArtifact> findByExecutionId(UUID executionId);

    List<ExecutionArtifact> findByExecutionIdAndType(UUID executionId, ArtifactType type);

    void deleteByExecutionId(UUID executionId);
}
