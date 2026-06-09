package com.icusu.sivan.agent.mcp;

import com.icusu.sivan.agent.tool.ToolIndex;
import com.icusu.sivan.agent.tool.ToolRegistryImpl;
import com.icusu.sivan.common.util.UrlValidator;
import com.icusu.sivan.domain.tool.McpServerConfig;
import com.icusu.sivan.domain.tool.IMcpServerConfigRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP 客户端连接管理器（全异步，零阻塞）。
 * <p>
 * 连接失败后 30s 冷却期，避免 MCP SDK 内部重连刷屏。
 */
@Slf4j
@Component
public class McpConnectionManager {

    private static final Duration COOLDOWN = Duration.ofSeconds(30);

    private final IMcpServerConfigRepository mcpServerConfigRepository;
    private final ToolIndex toolIndex;
    private final ToolRegistryImpl toolRegistry;

    private final Map<UUID, McpClientWrapper> connectedClients = new ConcurrentHashMap<>();
    private final Map<UUID, Instant> cooldowns = new ConcurrentHashMap<>();
    private final Set<UUID> connecting = ConcurrentHashMap.newKeySet();
    private final Set<Disposable> pendingConnects = ConcurrentHashMap.newKeySet();

    public McpConnectionManager(IMcpServerConfigRepository mcpServerConfigRepository,
                                ToolIndex toolIndex,
                                ToolRegistryImpl toolRegistry) {
        this.mcpServerConfigRepository = mcpServerConfigRepository;
        this.toolIndex = toolIndex;
        this.toolRegistry = toolRegistry;
    }

    @PostConstruct
    public void init() {
        // 启动时同步连接所有活跃服务器，确保首条消息即可使用 MCP 工具
        mcpServerConfigRepository.findAllActive().forEach(config -> {
            if (!isInCooldown(config.getServerId()) && !isConnected(config.getServerId())) {
                try {
                    McpClientWrapper client = doConnect(config);
                    connectedClients.put(config.getServerId(), client);
                    log.info("MCP 服务器已启动时连接: {} ({})", config.getName(), config.getServerUrl());
                } catch (Exception e) {
                    cooldowns.put(config.getServerId(), Instant.now());
                    log.warn("MCP 启动连接失败 ({}s 冷却): {} — {}",
                            COOLDOWN.toSeconds(), config.getName(), e.getMessage());
                }
            }
        });
    }

    @PreDestroy
    public void shutdown() {
        disconnectAll();
    }

    public boolean isConnected(UUID serverId) {
        return connectedClients.containsKey(serverId);
    }

    /** 异步连接（冷却期内跳过）。 */
    public void connectByServerId(UUID serverId) {
        if (isInCooldown(serverId)) return;
        mcpServerConfigRepository.findById(serverId)
                .ifPresentOrElse(this::connectAsync,
                        () -> log.warn("MCP 服务器不存在: {}", serverId));
    }

    /** 异步连接 active 的服务器（冷却期内跳过）。 */
    public void connectIfActive(UUID serverId) {
        if (isInCooldown(serverId)) return;
        if (isConnected(serverId)) return;

        mcpServerConfigRepository.findById(serverId).ifPresent(config -> {
            if (Boolean.TRUE.equals(config.getActive())) connectAsync(config);
        });
    }

    public void connectAll() {
        mcpServerConfigRepository.findAllActive().forEach(config -> {
            if (!isInCooldown(config.getServerId()) && !isConnected(config.getServerId())) {
                connectAsync(config);
            }
        });
    }

    /**
     * 同步连接服务器，阻塞直到连接完成或失败。
     * 用于消息发送前确保工具已就绪。
     */
    public void connectSync(UUID serverId) {
        if (isConnected(serverId)) return;
        if (isInCooldown(serverId)) return;
        mcpServerConfigRepository.findById(serverId).ifPresent(config -> {
            if (!Boolean.TRUE.equals(config.getActive())) return;
            try {
                McpClientWrapper client = doConnect(config);
                connectedClients.put(config.getServerId(), client);
                cooldowns.remove(config.getServerId());
                log.info("MCP 服务器已同步连接: {} ({})", config.getName(), config.getServerUrl());
            } catch (Exception e) {
                cooldowns.put(config.getServerId(), Instant.now());
                log.warn("MCP 同步连接失败 ({}s 冷却): {} — {}",
                        COOLDOWN.toSeconds(), config.getName(), e.getMessage());
            }
        });
    }

    public void disconnect(UUID serverId) {
        McpClientWrapper client = connectedClients.remove(serverId);
        cooldowns.remove(serverId);
        if (client != null) {
            toolRegistry.unregisterServerTools(serverId.toString());
            toolIndex.removeServer(serverId.toString());
            client.close();
        }
    }

    public void disconnectAll() {
        pendingConnects.forEach(d -> { if (!d.isDisposed()) d.dispose(); });
        pendingConnects.clear();
        connecting.clear();
        List.copyOf(connectedClients.keySet()).forEach(this::disconnect);
    }

    public int getConnectedCount() {
        return connectedClients.size();
    }

    public java.util.Optional<McpClientWrapper> getClient(UUID serverId) {
        return java.util.Optional.ofNullable(connectedClients.get(serverId));
    }

    // ====== 内部 ======

    private boolean isInCooldown(UUID serverId) {
        Instant last = cooldowns.get(serverId);
        return last != null && Instant.now().isBefore(last.plus(COOLDOWN));
    }

    private void connectAsync(McpServerConfig config) {
        if (config.getServerId() == null) return;
        if (!connecting.add(config.getServerId())) return; // 已在连接中

        Disposable disposable = Mono.fromCallable(() -> doConnect(config))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        client -> {
                            connectedClients.put(config.getServerId(), client);
                            cooldowns.remove(config.getServerId());
                            connecting.remove(config.getServerId());
                            pendingConnects.removeIf(d -> d.isDisposed());
                            log.info("MCP 服务器已连接: {} ({})", config.getName(), config.getServerUrl());
                        },
                        error -> {
                            cooldowns.put(config.getServerId(), Instant.now());
                            connecting.remove(config.getServerId());
                            pendingConnects.removeIf(d -> d.isDisposed());
                            log.warn("MCP 连接失败 ({}s 冷却): {} — {}",
                                    COOLDOWN.toSeconds(), config.getName(), error.getMessage());
                        }
                );
        pendingConnects.add(disposable);
    }

    private McpClientWrapper doConnect(McpServerConfig config) {
        // 默认 SSE（兼容大多数 MCP 服务器），仅 streamable-http 显式配置时使用
        boolean streamable = "streamable-http".equals(config.getTransport());
        try {
            return tryConnect(config, streamable);
        } catch (Exception e) {
            if (streamable) {
                log.warn("Streamable HTTP 失败，降级 SSE: {}", config.getName());
                return tryConnect(config, false);
            }
            throw e;
        }
    }

    private McpClientWrapper tryConnect(McpServerConfig config, boolean streamableHttp) {
        if (!streamableHttp) {
            String url = config.getServerUrl();
            // SSE 尝试 /sse 子路径（兼容常见 MCP 服务器部署），避免重复追加
            if (!url.endsWith("/sse") && !url.endsWith("/sse/")) {
                try {
                    return buildAndConnect(config, false, url + "/sse");
                } catch (Exception e) {
                    log.debug("SSE /sse 路径失败，尝试原 URL: {}", e.getMessage());
                }
            }
        }
        return buildAndConnect(config, streamableHttp, config.getServerUrl());
    }

    private McpClientWrapper buildAndConnect(McpServerConfig config, boolean streamableHttp, String url) {
        // SSRF 防护：运行时 DNS 解析 + 私有地址校验（防止 DNS rebinding）
        var urlCheck = UrlValidator.validatePrivateAccess(url);
        if (!urlCheck.valid()) {
            throw new RuntimeException("MCP 连接 URL 校验失败: " + urlCheck.errorMessage());
        }

        McpClientBuilder builder = McpClientBuilder.create(config.getName())
                .timeout(Duration.ofSeconds(60));
        if (streamableHttp) {
            builder.streamableHttpTransport(url);
        } else {
            builder.sseTransport(url);
        }
        if (config.getApiKey() != null && !config.getApiKey().isEmpty()) {
            builder.header("Authorization", "Bearer " + config.getApiKey());
        }

        McpClientWrapper client = builder.buildSync();
        toolIndex.indexServer(config.getServerId().toString(), config.getName(), client.listTools());
        toolRegistry.registerServerTools(config.getServerId().toString());
        return client;
    }
}
