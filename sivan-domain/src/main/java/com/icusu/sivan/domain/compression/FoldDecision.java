package com.icusu.sivan.domain.compression;

/** 折叠决策 — 决定一个节点是否需要被压缩。 */
public record FoldDecision(
        boolean shouldFold,
        String reason
) {
    public static FoldDecision skip(String reason) {
        return new FoldDecision(false, reason);
    }

    public static FoldDecision fold(String reason) {
        return new FoldDecision(true, reason);
    }
}
