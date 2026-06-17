package com.icusu.sivan.infra.forest.repository;

import com.icusu.sivan.infra.forest.entity.ForestExecutionLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface ForestExecutionLogJpaRepository extends JpaRepository<ForestExecutionLogEntity, UUID> {

    /** 查询某个节点在某个森林中的工具调用日志。 */
    @Query("SELECT e FROM ForestExecutionLogEntity e WHERE e.forestId = :forestId AND e.nodeId = :nodeId AND e.eventType = 'TOOL_CALL' ORDER BY e.createdAt ASC")
    List<ForestExecutionLogEntity> findToolCallsByNode(@Param("forestId") UUID forestId,
                                                        @Param("nodeId") String nodeId);

    /** 查询某个森林的所有工具调用日志（用于预加载）。 */
    @Query("SELECT e FROM ForestExecutionLogEntity e WHERE e.forestId = :forestId AND e.eventType = 'TOOL_CALL' ORDER BY e.createdAt ASC")
    List<ForestExecutionLogEntity> findToolCallsByForest(@Param("forestId") UUID forestId);
}
