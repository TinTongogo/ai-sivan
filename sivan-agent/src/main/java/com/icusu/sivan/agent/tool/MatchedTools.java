package com.icusu.sivan.agent.tool;

import com.icusu.sivan.core.tool.ToolSpec;
import com.icusu.sivan.domain.tool.ToolMeta;

import java.util.List;
import java.util.Map;

/**
 * 工具决议结果：匹配的工具元数据 + 对应的 LLM ToolSchema + 语义匹配分数。
 * <p>
 * {@code schemas} 传入 LLM API（工具定义），{@code metas} 用于提示词增强，
 * {@code toolScores} 记录各工具的语义相似度（调试/日志用），
 * {@code threshold} 语义匹配阈值，
 * {@code toolServerIds} 全部候选工具的 toolName → serverId 映射（用于日志记录，避免调用方外部查找）。
 */
public record MatchedTools(List<ToolMeta> metas, List<ToolSpec> schemas,
                           Map<String, Double> toolScores, double threshold,
                           Map<String, String> toolServerIds) {

    public MatchedTools(List<ToolMeta> metas, List<ToolSpec> schemas) {
        this(metas, schemas, Map.of(), 0, Map.of());
    }

    public boolean isEmpty() {
        return metas == null || metas.isEmpty();
    }

    public static MatchedTools empty() {
        return new MatchedTools(List.of(), List.of(), Map.of(), 0, Map.of());
    }
}
