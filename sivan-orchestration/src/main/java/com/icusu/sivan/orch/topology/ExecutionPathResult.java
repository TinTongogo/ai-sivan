package com.icusu.sivan.orch.topology;

import com.icusu.sivan.domain.task.ExecutionPath;

import java.util.UUID;

/**
 * 执行路径解析结果。由 {@link ExecutionPathResolver} 返回，
 * 告诉调用方是否命中模板、是否需要跳过 LLM classify。
 *
 * @param executionPath     解析出的执行路径（模板命中时非空）
 * @param fromTemplate      是否命中本能模板
 * @param patternId         命中的模板 ID（未命中时为 null）
 * @param shouldSkipClassify true=命中模板且未触发探索，可直接使用 executionPath
 */
public record ExecutionPathResult(
        ExecutionPath executionPath,
        boolean fromTemplate,
        UUID patternId,
        boolean shouldSkipClassify
) {
    public static ExecutionPathResult templateMatch(InstinctPatternWithPath pattern) {
        return new ExecutionPathResult(pattern.path, true, pattern.patternId, !pattern.exploring);
    }

    public static ExecutionPathResult noMatch() {
        return new ExecutionPathResult(null, false, null, false);
    }

    /**
     * 模板匹配的内部载体。
     */
    public record InstinctPatternWithPath(
            ExecutionPath path,
            UUID patternId,
            boolean exploring
    ) {}
}
