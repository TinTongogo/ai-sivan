package com.icusu.sivan.domain.forest.service.feedback;

import com.icusu.sivan.common.event.GoalTreeCompleted;

/**
 * 反馈处理器 — 监听 GoalTreeCompleted 事件，更新模板成功率。
 * <p>
 * 实现滑动窗口（最近 20 次）成功率计算：
 * <pre>
 * successRate = (prevRate * (window-1) + latest) / window
 * </pre>
 * 低成功率触发 {@link ExplorationScheduler} 探索替代方案。
 */
@FunctionalInterface
public interface FeedbackHandler {

    /**
     * 由 GoalTreeCompleted 领域事件触发。
     * @param event 执行完成事件（含 templateId、success 等）
     */
    void onGoalTreeCompleted(GoalTreeCompleted event);
}
