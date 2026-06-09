package com.icusu.sivan.domain.forest.context;

/**
 * 执行进度快照 — 由 ProgressStrategy 按 Mode 语义计算。
 */
public record Progress(
        int completed,
        int activated,
        int total,
        int depth
) {
    public static final Progress ZERO = new Progress(0, 0, 0, 0);
}
