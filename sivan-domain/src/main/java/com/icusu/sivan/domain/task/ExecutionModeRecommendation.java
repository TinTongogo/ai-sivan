package com.icusu.sivan.domain.task;

import com.icusu.sivan.common.enums.SquadMode;

/**
 * 执行模式推荐值对象。
 * 由 ExecutionModeSelector 基于 TaskFeatures 计算产出，
 * 供 TopologyGenerator 和 InstinctPattern 使用。
 *
 * @param shape     执行形态
 * @param squadMode 阶段间模式（CHAT 时为 null）
 * @param phaseMode 阶段内默认模式（CHAT/SINGLE_AGENT 时为 null）
 * @param reason    推荐理由
 */
public record ExecutionModeRecommendation(
        ExecutionShape shape,
        SquadMode squadMode,
        SquadMode phaseMode,
        String reason
) {

    public ExecutionModeRecommendation {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("推荐理由不能为空");
        }
        if (shape == ExecutionShape.CHAT) {
            if (squadMode != null || phaseMode != null) {
                throw new IllegalArgumentException("CHAT 形态不应指定编排模式");
            }
        }
        if (shape == ExecutionShape.SQUAD) {
            if (squadMode == null) {
                throw new IllegalArgumentException("SQUAD 形态必须指定阶段间模式");
            }
        }
    }
}
