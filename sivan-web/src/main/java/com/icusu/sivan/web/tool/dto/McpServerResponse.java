package com.icusu.sivan.web.tool.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * MCP 服务器响应 DTO（07-工具动态感知 §5.1）。
 */
@Data
@Builder
@AllArgsConstructor
public class McpServerResponse {

    private UUID serverId;
    private String name;
    private String serverUrl;
    private String apiKey;
    private String transport;
    private Boolean active;
    private String connectionStatus;
    private String lastError;
    private LocalDateTime lastConnectedAt;
    private Integer toolCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
