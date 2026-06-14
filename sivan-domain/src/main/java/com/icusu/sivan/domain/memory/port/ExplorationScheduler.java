package com.icusu.sivan.domain.memory.port;

import com.icusu.sivan.domain.memory.InstinctPattern;

/**
 * 探索调度器 — 按成功率决定探索策略。
 * <p>
 * 策略：
 * <ul>
 *   <li>successRate > 0.8 → 高成功率，增加权重</li>
 *   <li>0.4 < successRate < 0.8 → 中等，保持观察</li>
 *   <li>successRate < 0.4 → 低成功率，标记探索候选</li>
 * </ul>
 */
@FunctionalInterface
public interface ExplorationScheduler {

    /**
     * 检查一个模板是否需要探索替代方案。
     * @param pattern 已更新成功率的模板
     */
    void checkExploration(InstinctPattern pattern);
}
