package com.icusu.sivan.web.tool.controller;

import com.icusu.sivan.common.dto.BaseResponse;
import com.icusu.sivan.web.tool.dto.CreateMcpServerRequest;
import com.icusu.sivan.web.tool.dto.McpConnectionRequest;
import com.icusu.sivan.web.tool.dto.UpdateMcpServerRequest;
import com.icusu.sivan.web.tool.dto.McpServerResponse;
import com.icusu.sivan.domain.tool.PreflightResult;
import com.icusu.sivan.web.tool.dto.McpTestResult;
import com.icusu.sivan.web.tool.service.McpServerConfigService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import com.icusu.sivan.web.shared.security.CurrentAccountId;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.UUID;

/**
 * MCP 服务器配置管理控制器。
 */
@RestController
@RequestMapping("/api/v2/mcp-servers")
@RequiredArgsConstructor
public class McpServerConfigController {

    private final McpServerConfigService mcpServerConfigService;

    /** 创建 MCP 服务器配置。 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<BaseResponse<McpServerResponse>> create(@Valid @RequestBody CreateMcpServerRequest request, @CurrentAccountId UUID accountId) {
                return Mono.fromCallable(() -> BaseResponse.created(mcpServerConfigService.create(accountId, request)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** 根据 ID 获取 MCP 服务器配置。 */
    @GetMapping("/{serverId}")
    public BaseResponse<McpServerResponse> getById(@PathVariable UUID serverId, @CurrentAccountId UUID accountId) {
                return BaseResponse.success(mcpServerConfigService.getById(accountId, serverId));
    }

    /** 获取 MCP 服务器配置列表。 */
    @GetMapping
    public BaseResponse<List<McpServerResponse>> list(@CurrentAccountId UUID accountId) {
                return BaseResponse.success(mcpServerConfigService.list(accountId));
    }

    /** 更新 MCP 服务器配置。 */
    @PutMapping("/{serverId}")
    public Mono<BaseResponse<McpServerResponse>> update(@PathVariable UUID serverId,
                                                         @RequestBody UpdateMcpServerRequest request, @CurrentAccountId UUID accountId) {
                return Mono.fromCallable(() -> BaseResponse.success(mcpServerConfigService.update(accountId, serverId, request)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** 删除 MCP 服务器配置。 */
    @DeleteMapping("/{serverId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> delete(@PathVariable UUID serverId, @CurrentAccountId UUID accountId) {
                return Mono.fromRunnable(() -> mcpServerConfigService.delete(accountId, serverId))
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    /** 测试 MCP 服务器连接。 */
    @PostMapping("/test")
    public Mono<BaseResponse<McpTestResult>> testConnection(@Valid @RequestBody McpConnectionRequest request) {
        return Mono.fromCallable(() -> BaseResponse.success(mcpServerConfigService.testConnection(request)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    // =====================================================================
    // 以下为 07-工具动态感知 §5.2 新增 API
    // =====================================================================

    /** 手动连接 MCP 服务器。 */
    @PostMapping("/{serverId}/connect")
    public Mono<BaseResponse<McpServerResponse>> connect(@PathVariable UUID serverId, @CurrentAccountId UUID accountId) {
        return Mono.fromCallable(() -> BaseResponse.success(mcpServerConfigService.connect(accountId, serverId)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** 手动断开 MCP 服务器。 */
    @PostMapping("/{serverId}/disconnect")
    public Mono<BaseResponse<McpServerResponse>> disconnect(@PathVariable UUID serverId, @CurrentAccountId UUID accountId) {
        return Mono.fromCallable(() -> BaseResponse.success(mcpServerConfigService.disconnect(accountId, serverId)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** 查看 MCP 服务器提供的工具列表。 */
    @GetMapping("/{serverId}/tools")
    public Mono<BaseResponse<List<McpTestResult.ToolInfo>>> listTools(@PathVariable UUID serverId, @CurrentAccountId UUID accountId) {
        return Mono.fromCallable(() -> BaseResponse.success(mcpServerConfigService.listTools(accountId, serverId)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** 运行 MCP 服务器预检检查。 */
    @PostMapping("/{serverId}/preflight")
    public Mono<BaseResponse<List<PreflightResult>>> preflight(@PathVariable UUID serverId, @CurrentAccountId UUID accountId) {
        return Mono.fromCallable(() -> BaseResponse.success(mcpServerConfigService.preflight(accountId, serverId)))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
