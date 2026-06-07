package com.icusu.sivan.agent.tool;

/**
 * MCP 工具能力类型枚举。
 * <p>
 * 用于标记工具的语义能力，支持工具匹配和智能体自动编排中的能力对齐。
 */
public enum ToolCapability {
    QUERY,
    WRITE,
    CODE_EXECUTION,
    WEB_SEARCH,
    FILE_OPERATION;

    /** 获取存储用的简短标签。 */
    public String label() {
        return name().toLowerCase();
    }

    /** 从标签解析能力类型（忽略大小写）。 */
    public static ToolCapability fromLabel(String label) {
        if (label == null) return null;
        return switch (label.toLowerCase()) {
            case "query" -> QUERY;
            case "write" -> WRITE;
            case "code_execution" -> CODE_EXECUTION;
            case "web_search" -> WEB_SEARCH;
            case "file_operation" -> FILE_OPERATION;
            default -> null;
        };
    }
}
