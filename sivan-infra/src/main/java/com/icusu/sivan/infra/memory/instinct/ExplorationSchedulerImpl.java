package com.icusu.sivan.infra.memory.instinct;

import com.icusu.sivan.domain.memory.port.ExplorationScheduler;
import com.icusu.sivan.domain.memory.IInstinctPatternRepository;
import com.icusu.sivan.domain.memory.InstinctPattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 探索调度器实现 — 按成功率决定策略。
 * <p>
 * <ul>
 *   <li>样本不足 (<3次) → 不判定</li>
 *   <li>successRate > 0.8 → 高成功率，保持</li>
 *   <li>successRate < 0.4 → 低成功率，标记为不活跃</li>
 * </ul>
 */
@Component
public class ExplorationSchedulerImpl implements ExplorationScheduler {

    private static final Logger log = LoggerFactory.getLogger(ExplorationSchedulerImpl.class);

    private final IInstinctPatternRepository patternRepo;

    public ExplorationSchedulerImpl(IInstinctPatternRepository patternRepo) {
        this.patternRepo = patternRepo;
    }

    @Override
    public void checkExploration(InstinctPattern pattern) {
        if (pattern.getTotalCount() == null || pattern.getTotalCount() < 3) {
            return; // 样本不足，不判定
        }

        double rate = pattern.getSuccessRate() != null ? pattern.getSuccessRate() : 0.0;
        int total = pattern.getTotalCount();

        if (rate > 0.8) {
            log.debug("[探索] 高成功率模板: patternId={} rate={} total={}，保持",
                    pattern.getPatternId().toString().substring(0, 8),
                    String.format("%.2f", rate), total);
            // 高成功率 → 增加权重，已由 recordOutcome 自动处理
        } else if (rate < 0.4) {
            log.warn("[探索] 低成功率模板: patternId={} rate={} total={}，标记为不活跃",
                    pattern.getPatternId().toString().substring(0, 8),
                    String.format("%.2f", rate), total);
            pattern.setActive(false);
            patternRepo.update(pattern);
        } else {
            log.debug("[探索] 中等成功率模板: patternId={} rate={} total={}，保持观察",
                    pattern.getPatternId().toString().substring(0, 8),
                    String.format("%.2f", rate), total);
        }
    }
}
