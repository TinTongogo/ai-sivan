package com.icusu.sivan.domain.tool;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 智能体工具需求声明。
 * <ul>
 *   <li>autoMatch — 自动语义匹配（默认 true），用 craftDeclaration + systemPrompt 与工具描述做 embedding 相似度</li>
 *   <li>requiredCapabilities — 显式声明的能力列表，直接匹配工具标签</li>
 *   <li>preferredServers — 优先从哪些 MCP 服务器加载工具</li>
 *   <li>minConfidence — 语义匹配最低阈值（默认 0.65，与 AgentSkillMatcher 一致）</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolRequirement {
    @Builder.Default
    private boolean autoMatch = true;
    private List<String> requiredCapabilities;
    private List<String> preferredServers;
    @Builder.Default
    private double minConfidence = 0.65;
}
