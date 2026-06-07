package com.icusu.sivan.infra.feedback.repository;

import com.icusu.sivan.infra.feedback.entity.PatternFeedbackEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 本能模板反馈表数据访问接口。
 */
@Repository
public interface PatternFeedbackJpaRepository extends JpaRepository<PatternFeedbackEntity, UUID> {

    List<PatternFeedbackEntity> findByExecutionIdOrderByCreatedAtDesc(UUID executionId);

    List<PatternFeedbackEntity> findByPatternIdOrderByCreatedAtDesc(UUID patternId);

    List<PatternFeedbackEntity> findByAccountIdOrderByCreatedAtDesc(UUID accountId);

    List<PatternFeedbackEntity> findByCreatedAtBefore(OffsetDateTime cutoff);
}
