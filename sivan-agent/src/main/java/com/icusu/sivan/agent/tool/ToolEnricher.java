package com.icusu.sivan.agent.tool;

import com.icusu.sivan.agent.prompt.ToolPrompts;
import com.icusu.sivan.core.tool.ToolSpec;
import com.icusu.sivan.domain.tool.ToolMeta;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 工具提示词增强器。
 * <p>
 * 将匹配到的工具信息注入系统提示词，引导 LLM 主动调用工具。
 * 同时提供 ToolSpec 列表供 LLM API 使用。
 */
@Slf4j
@Component
public class ToolEnricher {

    /**
     * 在系统提示词末尾追加工具可用说明。
     * 工具描述文本由 {@link ToolPrompts#toolEnrichment} 统一生成。
     */
    public String enrichPrompt(String basePrompt, List<ToolMeta> matchedTools) {
        if (matchedTools == null || matchedTools.isEmpty()) {
            return basePrompt;
        }
        List<ToolPrompts.ToolMetaProjection> projections = matchedTools.stream()
                .map(t -> new ToolPrompts.ToolMetaProjection(
                        t.getToolName(), t.getDescription(), t.getServerName()))
                .collect(Collectors.toList());
        return basePrompt + ToolPrompts.toolEnrichment(projections).content();
    }

    /**
     * 从 MatchedTools 提取 ToolSpec 列表（已包含在 MatchedTools.schemas() 中，
     * 此方法仅做 null 安全适配，保持接口清晰）。
     */
    public List<ToolSpec> toSchemas(MatchedTools matched) {
        if (matched == null || matched.isEmpty()) {
            return List.of();
        }
        return matched.schemas();
    }
}
