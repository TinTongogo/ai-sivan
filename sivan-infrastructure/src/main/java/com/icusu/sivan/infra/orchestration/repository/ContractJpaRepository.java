package com.icusu.sivan.infra.orchestration.repository;

import com.icusu.sivan.infra.orchestration.entity.ContractEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * 智能体契约表数据访问接口。
 */
@Repository
public interface ContractJpaRepository extends JpaRepository<ContractEntity, UUID> {

    List<ContractEntity> findByExecutionId(UUID executionId);

    List<ContractEntity> findByExecutionIdAndPhase(UUID executionId, int phase);

    List<ContractEntity> findByExecutionIdIn(List<UUID> executionIds);

    void deleteByExecutionId(UUID executionId);
}
