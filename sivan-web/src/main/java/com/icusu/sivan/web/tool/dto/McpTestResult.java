package com.icusu.sivan.web.tool.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * MCP 连接测试结果。
 */
@Data
@Builder
@AllArgsConstructor
public class McpTestResult {

    private boolean success;
    private String message;
    private List<ToolInfo> tools;

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class ToolInfo {
        private String name;
        private String title;
        private String description;
        private java.util.Map<String, Object> inputSchema;
        private java.util.Map<String, Object> outputSchema;
        private java.util.Map<String, Object> annotations;
        private java.util.Map<String, Object> meta;
    }
}
