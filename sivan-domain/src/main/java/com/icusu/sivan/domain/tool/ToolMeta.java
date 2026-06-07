package com.icusu.sivan.domain.tool;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 工具元数据，包含工具名称、描述及其所属 MCP 服务器信息。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolMeta {
    private String toolName;
    private String title;
    private String description;
    private String serverId;
    private String serverName;
    private List<String> capabilities;
    private Map<String, Object> inputSchema;
}
