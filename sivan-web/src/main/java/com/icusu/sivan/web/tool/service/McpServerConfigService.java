package com.icusu.sivan.web.tool.service;

import com.icusu.sivan.agent.mcp.McpClientBuilder;
import com.icusu.sivan.agent.mcp.McpClientWrapper;
import com.icusu.sivan.agent.mcp.McpConnectionManager;
import com.icusu.sivan.common.exception.DomainException;
import com.icusu.sivan.common.exception.ResourceNotFoundException;
import com.icusu.sivan.domain.tool.IMcpServerConfigRepository;
import com.icusu.sivan.domain.tool.McpServerConfig;
import com.icusu.sivan.common.util.UrlValidator;
import com.icusu.sivan.web.tool.dto.*;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

@Slf4j
/** MCP 服务器配置管理服务，管理 MCP 工具服务器连接。
 * 功能涵盖：CRUD、连接/断开、工具列表查询、预检（07-工具动态感知）。 */
@Service
@RequiredArgsConstructor
public class McpServerConfigService {

    private final IMcpServerConfigRepository mcpServerConfigRepository;
    private final McpConnectionManager mcpConnectionManager;
    private final com.icusu.sivan.agent.tool.ToolPreflight toolPreflight;

    /**
     * 创建 MCP 服务器配置（仅保存到数据库，不建立连接）。连接在使用时按需建立。
     */
    public McpServerResponse create(UUID accountId, CreateMcpServerRequest request) {
        // SSRF 防护：serverUrl 入库前校验（DNS 解析 + 私有地址白名单）
        var urlCheck = UrlValidator.validatePrivateAccess(request.getServerUrl());
        if (!urlCheck.valid()) {
            throw new DomainException("服务器 URL 无效: " + urlCheck.errorMessage());
        }

        McpServerConfig config = McpServerConfig.builder()
                .accountId(accountId)
                .name(request.getName())
                .serverUrl(request.getServerUrl())
                .apiKey(request.getApiKey())
                .transport(request.getTransport() != null ? request.getTransport() : "sse")
                .active(true)
                .build();

        mcpServerConfigRepository.save(config);

        return toResponse(config);
    }

    /**
     * 根据 ID 查询 MCP 服务器。
     */
    public McpServerResponse getById(UUID accountId, UUID serverId) {
        return toResponse(findOwned(accountId, serverId));
    }

    /**
     * 查询 MCP 服务器列表。
     */
    public List<McpServerResponse> list(UUID accountId) {
        return mcpServerConfigRepository.findAllByAccount(accountId).stream()
                .map(this::toResponse).toList();
    }

    /**
     * 更新 MCP 服务器配置（仅更新数据库，不触发重连）。连接在下一次使用时按需建立。
     */
    public McpServerResponse update(UUID accountId, UUID serverId, UpdateMcpServerRequest request) {
        McpServerConfig config = findOwned(accountId, serverId);

        // SSRF 防护：serverUrl 入库前校验（DNS 解析 + 私有地址白名单）
        if (request.getServerUrl() != null && !request.getServerUrl().isBlank()) {
            var urlCheck = UrlValidator.validatePrivateAccess(request.getServerUrl());
            if (!urlCheck.valid()) {
                throw new DomainException("服务器 URL 无效: " + urlCheck.errorMessage());
            }
        }

        config.updateFrom(request.getName(), request.getServerUrl(), request.getApiKey(),
                request.getTransport(), request.getActive());
        mcpServerConfigRepository.save(config);

        // 如果更新后服务器被停用且当前已连接，断开连接
        if (!Boolean.TRUE.equals(config.getActive())) {
            mcpConnectionManager.disconnect(serverId);
        }

        return toResponse(config);
    }

    /**
     * 删除 MCP 服务器配置并断开连接。
     */
    public void delete(UUID accountId, UUID serverId) {
        McpServerConfig config = findOwned(accountId, serverId);
        mcpConnectionManager.disconnect(serverId);
        mcpServerConfigRepository.delete(config.getServerId());
    }

    /**
     * 测试 MCP 服务器连通性，streamable-http 失败自动降级 SSE。
     */
    public McpTestResult testConnection(McpConnectionRequest request) {
        // SSRF 防护：DNS 解析 + 私有地址白名单校验
        var urlCheck = UrlValidator.validatePrivateAccess(request.getServerUrl());
        if (!urlCheck.valid()) {
            return McpTestResult.builder().success(false)
                    .message("URL 无效: " + urlCheck.errorMessage()).build();
        }

        try {
            List<McpTestResult.ToolInfo> tools = tryConnect(request);
            return McpTestResult.builder().success(true).message("连接成功")
                    .tools(tools).build();
        } catch (Exception e) {
            log.warn("MCP 服务器连接测试失败: {}", e.getMessage());
            return McpTestResult.builder().success(false)
                    .message("连接失败: " + e.getMessage()).build();
        }
    }

    private List<McpTestResult.ToolInfo> tryConnect(McpConnectionRequest request) {
        String transport = request.getTransport();
        try {
            return buildAndTest(request, "streamable-http".equals(transport));
        } catch (Exception e) {
            if ("streamable-http".equals(transport)) {
                log.info("Streamable HTTP 失败，降级 SSE 测试: {}", e.getMessage());
                return buildAndTest(request, false);
            }
            throw e;
        }
    }

    private List<McpTestResult.ToolInfo> buildAndTest(McpConnectionRequest request, boolean streamableHttp) {
        McpClientBuilder builder = McpClientBuilder.create("test-connection").timeout(Duration.ofSeconds(10));
        if (streamableHttp) {
            builder.streamableHttpTransport(request.getServerUrl());
        } else {
            builder.sseTransport(request.getServerUrl());
        }
        if (request.getApiKey() != null && !request.getApiKey().isEmpty()) {
            builder.header("Authorization", "Bearer " + request.getApiKey());
        }
        McpClientWrapper client = builder.buildSync();
        List<McpSchema.Tool> tools = client.listTools();
        List<McpTestResult.ToolInfo> result = tools.stream()
                .map(t -> McpTestResult.ToolInfo.builder()
                        .name(t.name())
                        .title(t.title())
                        .description(t.description())
                        .inputSchema(t.inputSchema() != null
                                ? new com.fasterxml.jackson.databind.ObjectMapper().convertValue(t.inputSchema(), java.util.Map.class)
                                : null)
                        .outputSchema(t.outputSchema() != null
                                ? new com.fasterxml.jackson.databind.ObjectMapper().convertValue(t.outputSchema(), java.util.Map.class)
                                : null)
                        .annotations(t.annotations() != null
                                ? new com.fasterxml.jackson.databind.ObjectMapper().convertValue(t.annotations(), java.util.Map.class)
                                : null)
                        .meta(t.meta() != null ? t.meta() : null)
                        .build())
                .collect(java.util.stream.Collectors.toList());
        client.close();
        return result;
    }

    /**
     * 查找当前用户拥有的 MCP 服务器。
     */
    private McpServerConfig findOwned(UUID accountId, UUID serverId) {
        McpServerConfig config = mcpServerConfigRepository.findById(serverId)
                .orElseThrow(() -> ResourceNotFoundException.notFound("MCP 服务器", serverId));
        if (!config.getAccountId().equals(accountId)) {
            throw ResourceNotFoundException.notFound("MCP 服务器", serverId);
        }
        return config;
    }

    /**
     * 转换为响应对象。
     */
    private McpServerResponse toResponse(McpServerConfig config) {
        return McpServerResponse.builder()
                .serverId(config.getServerId())
                .name(config.getName())
                .serverUrl(config.getServerUrl())
                .apiKey(null) // 不再返回 API key
                .transport(config.getTransport())
                .active(config.getActive())
                .connectionStatus(config.getConnectionStatus())
                .lastError(config.getLastError())
                .lastConnectedAt(config.getLastConnectedAt())
                .toolCount(config.getToolCount())
                .createdAt(config.getCreatedAt())
                .updatedAt(config.getUpdatedAt())
                .build();
    }

    // =====================================================================
    // 新增：连接/断开/工具列表/预检（07-工具动态感知 §5.2）
    // =====================================================================

    /** 手动连接 MCP 服务器。 */
    public McpServerResponse connect(UUID accountId, UUID serverId) {
        McpServerConfig config = findOwned(accountId, serverId);
        mcpConnectionManager.connectSync(serverId);
        // 更新连接状态
        boolean connected = mcpConnectionManager.isConnected(serverId);
        config.setConnectionStatus(connected ? "CONNECTED" : "ERROR");
        if (connected) {
            config.setLastConnectedAt(java.time.LocalDateTime.now());
            config.setLastError(null);
        } else {
            config.setLastError("连接失败，请检查服务器地址和凭证");
        }
        mcpServerConfigRepository.save(config);
        return toResponse(config);
    }

    /** 手动断开 MCP 服务器。 */
    public McpServerResponse disconnect(UUID accountId, UUID serverId) {
        McpServerConfig config = findOwned(accountId, serverId);
        mcpConnectionManager.disconnect(serverId);
        config.setConnectionStatus("DISCONNECTED");
        mcpServerConfigRepository.save(config);
        return toResponse(config);
    }

    /** 查看 MCP 服务器的工具列表。 */
    public List<McpTestResult.ToolInfo> listTools(UUID accountId, UUID serverId) {
        McpServerConfig config = findOwned(accountId, serverId);
        var clientOpt = mcpConnectionManager.getClient(serverId);
        if (clientOpt.isEmpty()) {
            throw new com.icusu.sivan.common.exception.DomainException("MCP 服务器未连接，请先连接");
        }
        var client = clientOpt.get();
        List<McpSchema.Tool> tools = client.listTools();
        return tools.stream()
                .map(t -> McpTestResult.ToolInfo.builder()
                        .name(t.name())
                        .title(t.title())
                        .description(t.description())
                        .inputSchema(t.inputSchema() != null
                                ? new com.fasterxml.jackson.databind.ObjectMapper().convertValue(t.inputSchema(), java.util.Map.class)
                                : null)
                        .build())
                .toList();
    }

    /** 运行 MCP 服务器预检。 */
    public List<com.icusu.sivan.domain.tool.PreflightResult> preflight(UUID accountId, UUID serverId) {
        findOwned(accountId, serverId);
        return toolPreflight.check(serverId.toString()).block();
    }
}
