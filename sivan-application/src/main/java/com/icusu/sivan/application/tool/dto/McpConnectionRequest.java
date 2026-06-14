package com.icusu.sivan.application.tool.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * MCP 连接测试请求 DTO。
 */
@Data
public class McpConnectionRequest {

    @NotBlank
    private String serverUrl;

    private String apiKey;

    private String transport = "sse";
}
