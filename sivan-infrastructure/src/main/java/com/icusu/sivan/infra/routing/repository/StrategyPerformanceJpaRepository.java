package com.icusu.sivan.infra.routing.repository;

import com.icusu.sivan.infra.routing.entity.StrategyPerformanceEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StrategyPerformanceJpaRepository extends JpaRepository<StrategyPerformanceEntity, UUID> {
    Optional<StrategyPerformanceEntity> findByAccountIdAndStrategy(UUID accountId, String strategy);
    List<StrategyPerformanceEntity> findAllByAccountId(UUID accountId);
    void deleteAllByAccountId(UUID accountId);
}
