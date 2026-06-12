package com.icusu.sivan.agent.mcp;

import com.icusu.sivan.agent.tool.ToolIndex;
import com.icusu.sivan.agent.tool.ToolRegistryImpl;
import com.icusu.sivan.common.util.UrlValidator;
import com.icusu.sivan.domain.tool.McpServerConfig;
import com.icusu.sivan.domain.tool.IMcpServerConfigRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
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
import java.util.stream.Collectors;

/**
 * MCP 客户端连接管理器（全异步，零阻塞）。
 * <p>
 * 连接失败后 30s 冷却期，避免 MCP SDK 内部重连刷屏。
 * 每 60 秒心跳检测（07-工具动态感知 §5.3），连续 3 次失败自动断开。
 */
@Slf4j
@Component
public class McpConnectionManager {

    private static final Duration COOLDOWN = Duration.ofSeconds(30);
    private static final int MAX_HEARTBEAT_FAILURES = 3;

    private final IMcpServerConfigRepository mcpServerConfigRepository;
    private final ToolIndex toolIndex;
    private final ToolRegistryImpl toolRegistry;

    /** serverId → McpClientWrapper 连接池 */
    private final Map<UUID, McpClientWrapper> connectedClients = new ConcurrentHashMap<>();
    /** serverId → 连接元数据 */
    private final Map<UUID, McpConnection> connectionMeta = new ConcurrentHashMap<>();
    /** serverId → 冷却到期时间 */
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
                    connectionMeta.put(config.getServerId(), new McpConnection(config, ConnectionStatus.CONNECTED));
                    log.info("MCP 服务器已启动时连接: {} ({})", config.getName(), config.getServerUrl());
                } catch (Exception e) {
                    cooldowns.put(config.getServerId(), Instant.now());
                    connectionMeta.put(config.getServerId(), new McpConnection(config, ConnectionStatus.ERROR, e.getMessage()));
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

    /**
     * 心跳检测 — 每 60 秒检查所有活跃连接的健康状态（07-工具动态感知 §5.3）。
     * 连续 3 次失败自动断开并通知。
     */
    @Scheduled(fixedRate = 60000)
    public void heartbeat() {
        if (connectedClients.isEmpty()) return;

        List<UUID> deadServers = connectedClients.entrySet().stream()
                .filter(entry -> {
                    McpClientWrapper client = entry.getValue();
                    boolean alive = client.ping();
                    if (!alive) {
                        McpConnection meta = connectionMeta.get(entry.getKey());
                        if (meta != null) {
                            int failures = meta.heartbeatFailures + 1;
                            connectionMeta.put(entry.getKey(), meta.withHeartbeatFailures(failures));
                            if (failures >= MAX_HEARTBEAT_FAILURES) {
                                log.warn("MCP 心跳: 连续 {} 次失败，自动断开 serverId={} name={}",
                                        failures, entry.getKey(), client.getName());
                                return true; // 需要断开
                            }
                            log.warn("MCP 心跳: 第 {} 次失败 serverId={} name={}",
                                    failures, entry.getKey(), client.getName());
                        }
                    } else {
                        // 恢复正常，重置失败计数
                        McpConnection meta = connectionMeta.get(entry.getKey());
                        if (meta != null && meta.heartbeatFailures > 0) {
                            connectionMeta.put(entry.getKey(), meta.withHeartbeatFailures(0));
                        }
                    }
                    return false;
                })
                .map(Map.Entry::getKey)
                .toList();

        // 断开死服务器
        deadServers.forEach(serverId -> {
            notifyUser(serverId.toString(), "MCP 服务器连接断开（心跳检测失败）");
            disconnect(serverId);
        });

        if (!deadServers.isEmpty()) {
            log.info("MCP 心跳: 已断开 {} 个失效服务器", deadServers.size());
        }
    }

    public boolean isConnected(UUID serverId) {
        return connectedClients.containsKey(serverId);
    }

    /** 获取连接状态。 */
    public ConnectionStatus getStatus(UUID serverId) {
        McpConnection meta = connectionMeta.get(serverId);
        return meta != null ? meta.status : ConnectionStatus.DISCONNECTED;
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
                connectionMeta.put(config.getServerId(), new McpConnection(config, ConnectionStatus.CONNECTED));
                cooldowns.remove(config.getServerId());
                log.info("MCP 服务器已同步连接: {} ({})", config.getName(), config.getServerUrl());
            } catch (Exception e) {
                cooldowns.put(config.getServerId(), Instant.now());
                connectionMeta.put(config.getServerId(), new McpConnection(config, ConnectionStatus.ERROR, e.getMessage()));
                log.warn("MCP 同步连接失败 ({}s 冷却): {} — {}",
                        COOLDOWN.toSeconds(), config.getName(), e.getMessage());
            }
        });
    }

    public void disconnect(UUID serverId) {
        McpClientWrapper client = connectedClients.remove(serverId);
        cooldowns.remove(serverId);
        connectionMeta.remove(serverId);
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

    /** 获取所有已连接服务器的摘要信息。 */
    public List<ConnectionSummary> getConnectionSummaries() {
        return connectionMeta.entrySet().stream()
                .map(entry -> new ConnectionSummary(
                        entry.getKey().toString(),
                        entry.getValue().config.getName(),
                        entry.getValue().status.name(),
                        entry.getValue().lastError,
                        entry.getValue().config.getServerUrl()
                ))
                .toList();
    }

    // ====== 内部 ======

    private boolean isInCooldown(UUID serverId) {
        Instant last = cooldowns.get(serverId);
        return last != null && Instant.now().isBefore(last.plus(COOLDOWN));
    }

    private void connectAsync(McpServerConfig config) {
        if (config.getServerId() == null) return;
        if (!connecting.add(config.getServerId())) return; // 已在连接中

        connectionMeta.put(config.getServerId(), new McpConnection(config, ConnectionStatus.CONNECTING));

        Disposable disposable = Mono.fromCallable(() -> doConnect(config))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        client -> {
                            connectedClients.put(config.getServerId(), client);
                            connectionMeta.put(config.getServerId(), new McpConnection(config, ConnectionStatus.CONNECTED));
                            cooldowns.remove(config.getServerId());
                            connecting.remove(config.getServerId());
                            pendingConnects.removeIf(d -> d.isDisposed());
                            log.info("MCP 服务器已连接: {} ({})", config.getName(), config.getServerUrl());
                        },
                        error -> {
                            connectionMeta.put(config.getServerId(), new McpConnection(config, ConnectionStatus.ERROR, error.getMessage()));
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

    private void notifyUser(String serverId, String message) {
        log.warn("[MCP 通知] serverId={} {}", serverId, message);
    }

    // ====== 内部类型 ======

    /** MCP 连接元数据（07-工具动态感知 §5.3）。 */
    public record McpConnection(
            McpServerConfig config,
            ConnectionStatus status,
            String lastError,
            Instant lastConnectedAt,
            int heartbeatFailures
    ) {
        public McpConnection(McpServerConfig config, ConnectionStatus status) {
            this(config, status, null, Instant.now(), 0);
        }
        public McpConnection(McpServerConfig config, ConnectionStatus status, String lastError) {
            this(config, status, lastError, Instant.now(), 0);
        }
        public McpConnection withHeartbeatFailures(int failures) {
            return new McpConnection(config, status, lastError, lastConnectedAt, failures);
        }
    }

    /** 连接状态枚举（07-工具动态感知 §5.1）。 */
    public enum ConnectionStatus {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        ERROR
    }

    /** 连接摘要（供 API 返回）。 */
    public record ConnectionSummary(
            String serverId,
            String name,
            String status,
            String lastError,
            String serverUrl
    ) {}
}
