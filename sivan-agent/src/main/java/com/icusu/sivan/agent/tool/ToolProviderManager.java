package com.icusu.sivan.agent.tool;

import com.icusu.sivan.agent.mcp.McpConnectionManager;
import com.icusu.sivan.core.tool.ToolRegistry;
import com.icusu.sivan.domain.tool.McpServerConfig;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具提供者管理器 — 统一 MCP 连接生命周期管理（07-工具动态感知 §4.3）。
 * <p>
 * 职责：
 * <ul>
 *   <li>账号隔离：{@code Map<accountId, Map<serverId, ConnectionState>>}</li>
 *   <li>按需连接：{@link #connectIfNeeded(McpServerConfig, UUID)}</li>
 *   <li>运行时注册/注销</li>
 * </ul>
 * <p>
 * 实际 MCP 客户端连接委托给 {@link McpConnectionManager}。
 * 此管理类只负责跟踪账号维度的连接状态。
 */
@Component
public class ToolProviderManager {

    private static final Logger log = LoggerFactory.getLogger(ToolProviderManager.class);

    private final McpConnectionManager connectionManager;
    private final ToolRegistry registry;
    private final ToolCache cache;

    /**
     * 账号隔离的连接状态：{@code Map<accountId, Map<serverId, ConnectionState>>}
     */
    private final Map<UUID, Map<String, ConnectionState>> accountConnections = new ConcurrentHashMap<>();

    public ToolProviderManager(McpConnectionManager connectionManager,
                                ToolRegistry registry,
                                ToolCache cache) {
        this.connectionManager = connectionManager;
        this.registry = registry;
        this.cache = cache;
    }

    /**
     * 建立 MCP 连接并注册工具（异步）。
     */
    public Mono<Void> connectMcpServer(McpServerConfig config) {
        return Mono.fromRunnable(() -> {
            if (config.getServerId() == null || config.getAccountId() == null) {
                log.warn("connectMcpServer: 配置不完整，跳过");
                return;
            }
            connectionManager.connectByServerId(config.getServerId());
            trackConnection(config.getAccountId(), config.getServerId().toString(), config, true);
        });
    }

    /**
     * 断开 MCP 连接。
     */
    public void disconnectMcpServer(String serverId, UUID accountId) {
        removeTracking(accountId, serverId);
        UUID sid;
        try { sid = UUID.fromString(serverId); } catch (Exception e) { return; }
        connectionManager.disconnect(sid);
        cache.invalidate(accountId);
        log.info("ToolProviderManager: 已断开 serverId={} accountId={}", serverId, accountId);
    }

    /**
     * 按需连接——仅当对话明确选择某个 MCP 服务器时才建立连接。
     * 不启动、不预连接、不预先握手（07-工具动态感知 §4.3）。
     */
    public Mono<Void> connectIfNeeded(McpServerConfig config, UUID accountId) {
        if (isConnected(config.getServerId().toString(), accountId)) {
            return Mono.empty();
        }
        return connectMcpServer(config);
    }

    /** 检查指定服务器是否已为指定账号连接。 */
    public boolean isConnected(String serverId, UUID accountId) {
        Map<String, ConnectionState> serverMap = accountConnections.get(accountId);
        return serverMap != null && serverMap.containsKey(serverId)
                && connectionManager.isConnected(UUID.fromString(serverId));
    }

    /** 获取指定账号的已连接服务器 ID 列表。 */
    public Set<String> getConnectedServerIds(UUID accountId) {
        Map<String, ConnectionState> serverMap = accountConnections.get(accountId);
        return serverMap != null ? serverMap.keySet() : Set.of();
    }

    @PreDestroy
    public void shutdown() {
        accountConnections.clear();
    }

    // ====== 内部 ======

    private void trackConnection(UUID accountId, String serverId, McpServerConfig config, boolean connected) {
        accountConnections
                .computeIfAbsent(accountId, k -> new ConcurrentHashMap<>())
                .put(serverId, new ConnectionState(config, connected));
        cache.invalidate(accountId);
    }

    private void removeTracking(UUID accountId, String serverId) {
        Map<String, ConnectionState> serverMap = accountConnections.get(accountId);
        if (serverMap != null) {
            serverMap.remove(serverId);
            if (serverMap.isEmpty()) accountConnections.remove(accountId);
        }
    }

    /**
     * 连接状态记录。
     */
    private record ConnectionState(McpServerConfig config, boolean connected) {}
}
