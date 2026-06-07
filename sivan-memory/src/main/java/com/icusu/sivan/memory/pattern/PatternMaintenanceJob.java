package com.icusu.sivan.memory.pattern;

import com.icusu.sivan.domain.feedback.IPatternFeedbackRepository;
import com.icusu.sivan.domain.memory.IInstinctPatternRepository;
import com.icusu.sivan.domain.memory.InstinctPattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 本能模板定时维护任务。每日凌晨执行：
 * <ul>
 *   <li>T3: 连续失败模板降级（successRate < 0.3 且 totalCount ≥ 5）</li>
 *   <li>T4: 长期未命中模板归档（lastMatchAt > 30 天且 hitCount ≤ 3）</li>
 *   <li>过期反馈清理（createdAt > 90 天）</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PatternMaintenanceJob {

    private final IInstinctPatternRepository patternRepository;
    private final IPatternFeedbackRepository feedbackRepository;

    /** T3 降级：successRate < 0.3 */
    private static final double T3_DEGRADE_THRESHOLD = 0.3;

    /** T3 最小样本量：totalCount ≥ 5 */
    private static final int T3_MIN_SAMPLES = 5;

    /** T4 冷启天数：lastMatchAt > 30 天 */
    private static final int T4_COLD_DAYS = 30;

    /** T4 冷门命中上限 */
    private static final int T4_MAX_HIT_COUNT = 3;

    /** 反馈保留天数 */
    private static final int FEEDBACK_RETENTION_DAYS = 90;

    /**
     * 每日凌晨 3:07 执行维护。
     */
    @Scheduled(cron = "0 7 3 * * *")
    public void dailyMaintenance() {
        log.info("开始本能模板定时维护");
        int degraded = degradeFailingPatterns();
        int archived = archiveColdPatterns();
        int cleaned = cleanupExpiredFeedback();

        log.info("本能模板定时维护完成: 降级 {} 条 / 归档 {} 条 / 清理反馈 {} 条",
                degraded, archived, cleaned);
    }

    /**
     * T3: 连续失败模板降级。
     * 条件：successCount/totalCount < 0.3 且 totalCount ≥ 5 → active=false
     */
    int degradeFailingPatterns() {
        List<InstinctPattern> allActive = patternRepository.findAllActive();
        List<InstinctPattern> toDegrade = new ArrayList<>();

        for (InstinctPattern pattern : allActive) {
            int total = pattern.getTotalCount() != null ? pattern.getTotalCount() : 0;
            int success = pattern.getSuccessCount() != null ? pattern.getSuccessCount() : 0;

            if (total >= T3_MIN_SAMPLES) {
                double rate = (double) success / total;
                if (rate < T3_DEGRADE_THRESHOLD) {
                    toDegrade.add(pattern);
                }
            }
        }

        for (InstinctPattern pattern : toDegrade) {
            pattern.setActive(false);
            patternRepository.update(pattern);
            log.warn("T3 降级模板: patternId={}, success={}/{}",
                    pattern.getPatternId(), pattern.getSuccessCount(), pattern.getTotalCount());
        }

        return toDegrade.size();
    }

    /**
     * T4: 长期未命中模板归档。
     * 条件：lastMatchAt > 30 天前 且 hitCount ≤ 3 → active=false
     */
    int archiveColdPatterns() {
        List<InstinctPattern> allActive = patternRepository.findAllActive();
        LocalDateTime cutoff = LocalDateTime.now().minusDays(T4_COLD_DAYS);
        List<InstinctPattern> toArchive = new ArrayList<>();

        for (InstinctPattern pattern : allActive) {
            if (pattern.getLastMatchAt() == null) continue;
            if (pattern.getHitCount() == null) continue;

            if (pattern.getLastMatchAt().isBefore(cutoff)
                    && pattern.getHitCount() <= T4_MAX_HIT_COUNT) {
                toArchive.add(pattern);
            }
        }

        for (InstinctPattern pattern : toArchive) {
            pattern.setActive(false);
            patternRepository.update(pattern);
            log.warn("T4 归档模板: patternId={}, lastMatchAt={}, hitCount={}",
                    pattern.getPatternId(), pattern.getLastMatchAt(), pattern.getHitCount());
        }

        return toArchive.size();
    }

    /**
     * 清理过期反馈记录（createdAt > 90 天的记录）。
     */
    int cleanupExpiredFeedback() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(FEEDBACK_RETENTION_DAYS);
        var expired = feedbackRepository.findByCreatedAtBefore(cutoff);
        // 逐条删除（使用仓储的 save 标记或直接删除）
        for (var record : expired) {
            feedbackRepository.deleteByFeedbackId(record.getFeedbackId());
        }
        return expired.size();
    }
}
