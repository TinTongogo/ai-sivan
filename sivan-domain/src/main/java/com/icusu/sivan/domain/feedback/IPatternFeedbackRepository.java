package com.icusu.sivan.domain.feedback;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 本能模板反馈记录仓储接口。
 */
public interface IPatternFeedbackRepository {

    void save(PatternFeedbackRecord record);

    List<PatternFeedbackRecord> findByExecutionId(UUID executionId);

    List<PatternFeedbackRecord> findByPatternId(UUID patternId);

    /** 查询指定时间之前创建的反馈记录（清理用）。 */
    List<PatternFeedbackRecord> findByCreatedAtBefore(LocalDateTime cutoff);

    /** 删除指定反馈记录。 */
    void deleteByFeedbackId(UUID feedbackId);
}
