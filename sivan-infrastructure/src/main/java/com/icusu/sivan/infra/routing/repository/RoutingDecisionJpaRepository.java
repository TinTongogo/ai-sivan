package com.icusu.sivan.infra.routing.repository;

import com.icusu.sivan.infra.routing.entity.RoutingDecisionEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * 路由决策表数据访问接口。
 */
@Repository
public interface RoutingDecisionJpaRepository extends JpaRepository<RoutingDecisionEntity, UUID> {

    List<RoutingDecisionEntity> findByAccountId(UUID accountId);

    List<RoutingDecisionEntity> findByAccountIdAndStrategy(UUID accountId, String strategy);

    Page<RoutingDecisionEntity> findByAccountIdOrderByCreatedAtDesc(UUID accountId, Pageable pageable);

    Page<RoutingDecisionEntity> findByAccountIdAndStrategyOrderByCreatedAtDesc(UUID accountId, String strategy, Pageable pageable);

    long countByAccountId(UUID accountId);
}
