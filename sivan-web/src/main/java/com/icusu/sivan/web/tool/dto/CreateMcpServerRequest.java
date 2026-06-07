package com.icusu.sivan.web.tool.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 创建 MCP 服务器请求 DTO。
 */
@Data
public class CreateMcpServerRequest {

    @NotBlank(message = "名称为必填项")
    private String name;

    @NotBlank(message = "服务器地址为必填项")
    private String serverUrl;

    private String apiKey;

    private String transport;
}
