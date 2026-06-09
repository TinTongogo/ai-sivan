package com.icusu.sivan.domain.forest.context;

/**
 * 单节点的上下文快照 — 当前执行状态和输出。
 */
public record NodeContext(
        String nodeId,
        String content,
        Progress progress
) {}
