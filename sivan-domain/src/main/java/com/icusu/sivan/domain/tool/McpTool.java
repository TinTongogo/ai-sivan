package com.icusu.sivan.domain.tool;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * MCP 工具持久化实体，对应 McpSchema.Tool 的完整信息。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class McpTool {

    private UUID toolId;
    private UUID serverId;
    private String name;
    private String title;
    private String description;
    private Map<String, Object> inputSchema;
    private Map<String, Object> outputSchema;
    private Map<String, Object> annotations;
    private Map<String, Object> meta;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
