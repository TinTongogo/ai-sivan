package com.icusu.sivan.domain.forest.context;

/**
 * 执行进度快照 — 由 ProgressStrategy 按 Mode 语义计算。
 *
 * @param completed 已完成节点数（正常完成）
 * @param failed    已失败节点数
 * @param activated 已触及节点数（completed + failed + running）
 * @param total     节点总数
 * @param depth     树深度
 */
public record Progress(
        int completed,
        int failed,
        int activated,
        int total,
        int depth
) {
    public static final Progress ZERO = new Progress(0, 0, 0, 0, 0);
}
