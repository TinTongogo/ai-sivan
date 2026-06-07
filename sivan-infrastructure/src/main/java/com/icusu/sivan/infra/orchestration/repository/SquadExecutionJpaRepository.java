package com.icusu.sivan.infra.orchestration.repository;

import com.icusu.sivan.infra.orchestration.entity.SquadExecutionEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Squad 执行记录表数据访问接口。
 */
@Repository
public interface SquadExecutionJpaRepository extends JpaRepository<SquadExecutionEntity, UUID>,
        JpaSpecificationExecutor<SquadExecutionEntity> {

    List<SquadExecutionEntity> findByAccountId(UUID accountId);

    List<SquadExecutionEntity> findBySquadId(UUID squadId);

    List<SquadExecutionEntity> findBySquadIdAndAccountId(UUID squadId, UUID accountId);

    long countByAccountId(UUID accountId);

    long countBySquadId(UUID squadId);

    List<SquadExecutionEntity> findTop5ByAccountIdOrderByCreatedAtDesc(UUID accountId);

    void deleteBySquadId(UUID squadId);

    Page<SquadExecutionEntity> findBySquadId(UUID squadId, Pageable pageable);

    long countByAccountIdAndStatus(UUID accountId, String status);

    @Query("SELECT COUNT(e) FROM SquadExecutionEntity e WHERE e.accountId = :accountId AND e.status = :status AND e.completedAt >= :since")
    long countByAccountIdAndStatusSince(@Param("accountId") UUID accountId, @Param("status") String status, @Param("since") OffsetDateTime since);

    @Modifying
    @Query("UPDATE SquadExecutionEntity e SET e.agentState = :agentState WHERE e.executionId = :executionId")
    void updateAgentState(@Param("executionId") UUID executionId, @Param("agentState") String agentState);

    Page<SquadExecutionEntity> findByAccountId(UUID accountId, Pageable pageable);
}
