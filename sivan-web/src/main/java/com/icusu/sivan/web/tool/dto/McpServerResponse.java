package com.icusu.sivan.web.tool.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * MCP 服务器响应 DTO。
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
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
