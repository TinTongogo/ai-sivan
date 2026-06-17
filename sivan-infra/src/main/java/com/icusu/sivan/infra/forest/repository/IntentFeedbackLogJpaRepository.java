package com.icusu.sivan.infra.forest.repository;

import com.icusu.sivan.infra.forest.entity.IntentFeedbackLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 意图分类反馈日志数据访问接口。
 */
@Repository
public interface IntentFeedbackLogJpaRepository extends JpaRepository<IntentFeedbackLogEntity, UUID> {

    List<IntentFeedbackLogEntity> findByAccountIdOrderByCreatedAtDesc(UUID accountId, org.springframework.data.domain.Pageable pageable);

    Optional<IntentFeedbackLogEntity> findByMessageId(UUID messageId);

    long countByAccountIdAndUserCorrectionIsNotNull(UUID accountId);

    long countByAccountIdAndUserCorrection(UUID accountId, String userCorrection);
}
