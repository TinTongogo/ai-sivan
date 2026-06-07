package com.icusu.sivan.infra.orchestration.repository;

import com.icusu.sivan.infra.orchestration.entity.HitlReviewEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 人工审查表数据访问接口。
 */
@Repository
public interface HitlReviewJpaRepository extends JpaRepository<HitlReviewEntity, UUID> {

    List<HitlReviewEntity> findByExecutionId(UUID executionId);

    List<HitlReviewEntity> findByAccountIdAndStatus(UUID accountId, String status);

    List<HitlReviewEntity> findByExecutionIdAndPhase(UUID executionId, int phase);

    List<HitlReviewEntity> findByStatusAndExpiresAtBefore(String status, OffsetDateTime before);

    void deleteByExecutionId(UUID executionId);
}
