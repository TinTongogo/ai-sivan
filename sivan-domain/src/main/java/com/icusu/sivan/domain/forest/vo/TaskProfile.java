package com.icusu.sivan.domain.forest.vo;

/**
 * 任务画像 — 由调用方在请求模型时提供。
 * ModelRouter 据此选择最优模型。
 */
public record TaskProfile(
        boolean requiresThinking,
        boolean requiresVision,
        boolean requiresToolUse,
        int estimatedInputTokens,
        boolean isSimple,
        String taskType
) {
    public static TaskProfile simple() {
        return new TaskProfile(false, false, false, 500, true, "chat");
    }

    public static TaskProfile complex() {
        return new TaskProfile(true, false, true, 8000, false, "analysis");
    }
}
