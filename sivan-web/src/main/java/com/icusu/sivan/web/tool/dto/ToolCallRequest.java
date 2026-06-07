package com.icusu.sivan.web.tool.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * MCP 工具调用请求。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ToolCallRequest {
    @NotBlank(message = "toolName 不能为空")
    @Size(max = 128, message = "toolName 最长 128 个字符")
    private String toolName;

    private Map<String, Object> arguments;
}
