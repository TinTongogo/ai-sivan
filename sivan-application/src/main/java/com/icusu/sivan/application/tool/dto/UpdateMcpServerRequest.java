package com.icusu.sivan.application.tool.dto;

import lombok.Data;

/**
 * 更新 MCP 服务器请求 DTO。
 */
@Data
public class UpdateMcpServerRequest {

    private String name;
    private String serverUrl;
    private String apiKey;
    private String transport;
    private Boolean active;
}
