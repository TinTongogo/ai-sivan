package com.icusu.sivan.web.tool.controller;

import com.icusu.sivan.agent.mcp.McpServer;
import com.icusu.sivan.common.dto.BaseResponse;
import com.icusu.sivan.web.tool.dto.ToolCallRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import com.icusu.sivan.web.shared.security.CurrentAccountId;
import java.util.Map;
import java.util.UUID;

/**
 * MCP 协议端点。
 * 对外暴露 Sivan 工具能力，兼容 Model Context Protocol 规范。
 */
@Slf4j
@RestController
@RequestMapping("/api/mcp")
@RequiredArgsConstructor
public class McpController {
    private final McpServer mcpServer;

    /**
     * 获取 MCP 服务器信息（协议版本、工具列表）。
     */
    @GetMapping("/info")
    public Mono<BaseResponse<Map<String, Object>>> info() {
        return Mono.just(BaseResponse.success(Map.of(
                "protocolVersion", "2025-03-26",
                "tools", mcpServer.handleListTools()
        )));
    }

    /**
     * 列出所有可用工具。
     */
    @GetMapping("/tools")
    public Mono<BaseResponse<Object>> listTools() {
        return Mono.just(BaseResponse.success(mcpServer.handleListTools()));
    }

    /**
     * 调用指定工具。
     *
     * @param body 请求体，包含 toolName 和 arguments
     */
    @PostMapping("/call")
    public Mono<BaseResponse<Object>> callTool(@Valid @RequestBody ToolCallRequest request, @CurrentAccountId UUID accountId) {
        return mcpServer.handleCallTool(request.getToolName(), request.getArguments(), accountId)
                .map(result -> {
                    boolean isError = result.has("isError") && result.get("isError").asBoolean();
                    if (isError) {
                        return BaseResponse.badRequest(result.get("content").asText());
                    }
                    return BaseResponse.success(result);
                });
    }
}
