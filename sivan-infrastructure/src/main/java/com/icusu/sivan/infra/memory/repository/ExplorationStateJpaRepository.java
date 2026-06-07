package com.icusu.sivan.infra.memory.repository;

import com.icusu.sivan.infra.memory.entity.ExplorationStateEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * 探索决策状态 JPA 仓储。
 */
@Repository
public interface ExplorationStateJpaRepository extends JpaRepository<ExplorationStateEntity, UUID> {
}
