package com.icusu.sivan.infra.orchestration.adapter;

import com.icusu.sivan.domain.orchestration.HitlReview;
import com.icusu.sivan.domain.orchestration.IHitlReviewRepository;
import com.icusu.sivan.infra.orchestration.entity.HitlReviewEntity;
import com.icusu.sivan.infra.orchestration.repository.HitlReviewJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * HITL 审核仓储适配器，实现 IHitlReviewRepository。
 */
@Component
@RequiredArgsConstructor
public class HitlReviewRepositoryAdapter implements IHitlReviewRepository {

    private final HitlReviewJpaRepository jpaRepository;

    /** 根据 ID 查询审核记录。 */
    @Override
    public Optional<HitlReview> findById(UUID reviewId) {
        return jpaRepository.findById(reviewId).map(this::toDomain);
    }

    /** 根据执行 ID 查询审核记录。 */
    @Override
    public List<HitlReview> findByExecutionId(UUID executionId) {
        return jpaRepository.findByExecutionId(executionId).stream().map(this::toDomain).toList();
    }

    /** 查询账号下待处理的审核记录。 */
    @Override
    public List<HitlReview> findPendingByAccount(UUID accountId) {
        return jpaRepository.findByAccountIdAndStatus(accountId, "PENDING")
                .stream().map(this::toDomain).toList();
    }

    /** 根据执行 ID 和阶段查询审核记录（同一阶段可能存在多轮暂停）。 */
    @Override
    public List<HitlReview> findByExecutionIdAndPhase(UUID executionId, int phase) {
        return jpaRepository.findByExecutionIdAndPhase(executionId, phase)
                .stream().map(this::toDomain).toList();
    }

    /** 保存审核记录，回写 ID 和时间戳。 */
    @Override
    public void save(HitlReview review) {
        HitlReviewEntity entity = toEntity(review);
        jpaRepository.save(entity);
        if (review.getReviewId() == null) {
            review.setReviewId(entity.getReviewId());
        }
        review.setCreatedAt(entity.getCreatedAt() != null ? entity.getCreatedAt().toLocalDateTime() : null);
        review.setUpdatedAt(entity.getUpdatedAt() != null ? entity.getUpdatedAt().toLocalDateTime() : null);
    }

    /** 更新审核记录。 */
    @Override
    public void update(HitlReview review) {
        HitlReviewEntity entity = jpaRepository.findById(review.getReviewId()).orElse(null);
        if (entity == null) return;
        entity.setHumanFeedback(review.getHumanFeedback());
        entity.setStatus(review.getStatus());
        entity.setOutputContent(review.getOutputContent());
        entity.setCorrectedContent(review.getCorrectedContent());
        entity.setRestartHint(review.getRestartHint());
        entity.setRestartAgent(review.getRestartAgent());
        jpaRepository.save(entity);
        review.setUpdatedAt(entity.getUpdatedAt() != null ? entity.getUpdatedAt().toLocalDateTime() : null);
    }

    /** 删除执行相关的所有审核记录。 */
    @Override
    public void deleteByExecutionId(UUID executionId) {
        jpaRepository.deleteByExecutionId(executionId);
    }

    /** 查找已过期的待审核记录。 */
    @Override
    public List<HitlReview> findPendingExpired(LocalDateTime before) {
        return jpaRepository.findByStatusAndExpiresAtBefore("PENDING", before.atOffset(ZoneOffset.UTC))
                .stream().map(this::toDomain).toList();
    }

    /** 将实体转换为领域对象。 */
    private HitlReview toDomain(HitlReviewEntity entity) {
        return HitlReview.builder()
                .reviewId(entity.getReviewId())
                .executionId(entity.getExecutionId())
                .accountId(entity.getAccountId())
                .phase(entity.getPhase())
                .phaseName(entity.getPhaseName())
                .inputContent(entity.getInputContent())
                .outputContent(entity.getOutputContent())
                .humanFeedback(entity.getHumanFeedback())
                .status(entity.getStatus())
                .correctedContent(entity.getCorrectedContent())
                .restartHint(entity.getRestartHint())
                .restartAgent(entity.getRestartAgent())
                .expiresAt(entity.getExpiresAt() != null ? entity.getExpiresAt().toLocalDateTime() : null)
                .createdAt(entity.getCreatedAt() != null ? entity.getCreatedAt().toLocalDateTime() : null)
                .updatedAt(entity.getUpdatedAt() != null ? entity.getUpdatedAt().toLocalDateTime() : null)
                .build();
    }

    /** 将领域对象转换为实体。 */
    private HitlReviewEntity toEntity(HitlReview review) {
        HitlReviewEntity entity = new HitlReviewEntity();
        entity.setReviewId(review.getReviewId());
        entity.setExecutionId(review.getExecutionId());
        entity.setAccountId(review.getAccountId());
        entity.setPhase(review.getPhase());
        entity.setPhaseName(review.getPhaseName());
        entity.setInputContent(review.getInputContent());
        entity.setOutputContent(review.getOutputContent());
        entity.setHumanFeedback(review.getHumanFeedback());
        entity.setStatus(review.getStatus() != null ? review.getStatus() : "PENDING");
        entity.setCorrectedContent(review.getCorrectedContent());
        entity.setRestartHint(review.getRestartHint());
        entity.setRestartAgent(review.getRestartAgent());
        entity.setExpiresAt(review.getExpiresAt() != null ? review.getExpiresAt().atOffset(ZoneOffset.UTC) : null);
        return entity;
    }
}
