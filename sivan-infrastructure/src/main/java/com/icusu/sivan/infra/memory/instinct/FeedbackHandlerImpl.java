package com.icusu.sivan.infra.memory.instinct;

import com.icusu.sivan.common.event.GoalTreeCompleted;
import com.icusu.sivan.domain.forest.service.feedback.FeedbackHandler;
import com.icusu.sivan.domain.memory.IInstinctPatternRepository;
import com.icusu.sivan.domain.memory.InstinctPattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * 反馈处理器 — 通过 @EventListener 监听 GoalTreeCompleted 事件。
 * <p>
 * 更新模板的滑动窗口成功率和权重，触发探索检查。
 */
@Component
public class FeedbackHandlerImpl implements FeedbackHandler {

    private static final Logger log = LoggerFactory.getLogger(FeedbackHandlerImpl.class);

    private final IInstinctPatternRepository patternRepo;
    private final ExplorationSchedulerImpl explorer;

    public FeedbackHandlerImpl(IInstinctPatternRepository patternRepo, ExplorationSchedulerImpl explorer) {
        this.patternRepo = patternRepo;
        this.explorer = explorer;
    }

    @Override
    @EventListener
    public void onGoalTreeCompleted(GoalTreeCompleted event) {
        if (event.templateId() == null) {
            return; // 非模板创建的 GoalTree 不处理
        }

        UUID templateId;
        try {
            templateId = UUID.fromString(event.templateId());
        } catch (Exception e) {
            log.warn("[反馈] 无效的 templateId: {}", event.templateId());
            return;
        }

        Optional<InstinctPattern> opt = patternRepo.findById(templateId);
        if (opt.isEmpty()) {
            log.debug("[反馈] 模板不存在: templateId={}", templateId);
            return;
        }

        InstinctPattern pattern = opt.get();
        pattern.recordOutcome(event.success());
        patternRepo.update(pattern);

        log.info("[反馈] 模板 {} 成功率={} 权重={} (总执行: {})",
                templateId.toString().substring(0, 8),
                String.format("%.2f", pattern.getSuccessRate() != null ? pattern.getSuccessRate() : 0),
                String.format("%.2f", pattern.getWeight() != null ? pattern.getWeight() : 0),
                pattern.getTotalCount());

        // 触发探索检查
        explorer.checkExploration(pattern);
    }
}
