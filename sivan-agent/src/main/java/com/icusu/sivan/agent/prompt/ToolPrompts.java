package com.icusu.sivan.agent.prompt;

import java.util.stream.Collectors;

/**
 * 工具描述提示词。统一以「灵枢（Sivan）」为唯一人格。
 */
public final class ToolPrompts {

    private ToolPrompts() {}

    /** 工具描述 LRU 缓存。 */
    private static final int TOOL_CACHE_MAX = 128;
    @SuppressWarnings("serial")
    private static final java.util.LinkedHashMap<String, Prompt> TOOL_CACHE =
            new java.util.LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, Prompt> eldest) {
                    return size() > TOOL_CACHE_MAX;
                }
            };

    public static Prompt toolEnrichment(java.util.List<ToolMetaProjection> tools) {
        if (tools == null || tools.isEmpty()) return Prompt.EMPTY;
        String cacheKey = tools.stream()
                .map(t -> t.toolName() + "|" + t.serverName())
                .sorted().collect(Collectors.joining(","));
        synchronized (TOOL_CACHE) {
            Prompt cached = TOOL_CACHE.get(cacheKey);
            if (cached != null) return cached;
        }
        StringBuilder sb = new StringBuilder("\n\n## 可用工具\n");
        sb.append("可以调用以下工具来帮助用户。当用户需求涉及实时信息、外部服务时，请主动调用合适的工具：\n");
        for (var t : tools) {
            sb.append("- **").append(PromptUtils.escapeUserInput(t.toolName())).append("**");
            if (t.description() != null && !t.description().isBlank()) {
                sb.append("：").append(PromptUtils.escapeUserInput(t.description()));
            }
            sb.append("\n");
        }
        sb.append("（工具来自 MCP 服务器: ");
        sb.append(tools.stream().map(ToolMetaProjection::serverName).distinct().collect(Collectors.joining("、")));
        sb.append("）\n");
        String content = sb.toString();
        Prompt prompt = new Prompt(content, Prompt.CacheStrategy.SESSION_STABLE,
                20 + tools.size() * 15, Prompt.OutputFormat.FREE_TEXT);
        PromptUtils.recordCall("toolEnrichment");
        synchronized (TOOL_CACHE) {
            TOOL_CACHE.put(cacheKey, prompt);
        }
        return prompt;
    }

    public record ToolMetaProjection(String toolName, String description, String serverName) {}
}
