package com.icusu.sivan.domain.orchestration;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * HITL 审核记录仓储接口。
 */
public interface IHitlReviewRepository {

    /** 根据 ID 查找审核记录。 */
    Optional<HitlReview> findById(UUID reviewId);

    /** 根据执行 ID 查找审核记录列表。 */
    List<HitlReview> findByExecutionId(UUID executionId);

    /** 查找指定用户的待审核记录。 */
    List<HitlReview> findPendingByAccount(UUID accountId);

    /** 根据执行 ID 和阶段查找审核记录。 */
    List<HitlReview> findByExecutionIdAndPhase(UUID executionId, int phase);

    /** 保存审核记录。 */
    void save(HitlReview review);

    /** 更新审核记录。 */
    void update(HitlReview review);

    /** 删除执行相关的所有审核记录。 */
    void deleteByExecutionId(UUID executionId);

    /** 查找已过期的待审核记录。 */
    List<HitlReview> findPendingExpired(LocalDateTime before);
}
