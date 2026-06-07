package com.icusu.sivan.agent.tool;

import com.icusu.sivan.domain.tool.IMcpToolRepository;
import com.icusu.sivan.domain.tool.McpTool;
import com.icusu.sivan.domain.tool.ToolMeta;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

/**
 * MCP 工具索引，维护 serverId → 工具列表的映射。
 * <p>
 * 启动时从 DB 加载持久化的工具定义，运行时由 {@link com.icusu.sivan.agent.mcp.McpConnectionManager}
 * 在连接/断开时更新连接状态。DB 作为工具定义源，内存作为缓存，断连后工具定义保留。
 * <p>
 * 供 {@link ToolResolver} 查询匹配。
 */
@Slf4j
@Component
public class ToolIndex {

    private final ConcurrentMap<String, ServerEntry> serverTools = new ConcurrentHashMap<>();
    private final Set<String> connectedServers = ConcurrentHashMap.newKeySet();
    private final ToolCapabilityRegistry toolCapabilityRegistry;
    private final IMcpToolRepository mcpToolRepository;
    private final ObjectMapper objectMapper;

    public ToolIndex(ToolCapabilityRegistry toolCapabilityRegistry, IMcpToolRepository mcpToolRepository) {
        this.toolCapabilityRegistry = toolCapabilityRegistry;
        this.mcpToolRepository = mcpToolRepository;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 启动时从 DB 加载所有已持久化的工具定义。
     */
    @PostConstruct
    void loadFromDb() {
        try {
            List<McpTool> dbTools = mcpToolRepository.findAll();
            if (dbTools.isEmpty()) return;

            Map<UUID, List<McpTool>> byServer = dbTools.stream()
                    .collect(Collectors.groupingBy(McpTool::getServerId));
            for (var entry : byServer.entrySet()) {
                String serverId = entry.getKey().toString();
                // 从第一个工具推断 serverName（DB 工具没有 serverName，用 serverId 兜底）
                String serverName = entry.getValue().stream()
                        .findFirst()
                        .map(t -> t.getServerId().toString())
                        .orElse(serverId);
                List<ToolMeta> metas = entry.getValue().stream()
                        .map(this::toToolMeta)
                        .toList();
                serverTools.put(serverId, new ServerEntry(serverName, metas, false));
            }
            log.info("从 DB 加载 {} 个服务器的 {} 个工具定义", byServer.size(), dbTools.size());
        } catch (Exception e) {
            log.warn("从 DB 加载工具定义失败: {}", e.getMessage());
        }
    }

    /**
     * 索引一个 MCP 服务器的工具列表（连接成功后调用）。
     */
    public void indexServer(String serverId, String serverName, List<McpSchema.Tool> tools) {
        UUID serverUuid = UUID.fromString(serverId);
        // 先清除旧工具记录，再批量写入新工具
        try {
            mcpToolRepository.deleteByServerId(serverUuid);
        } catch (Exception e) {
            log.warn("清除 MCP 工具记录失败: serverId={}, {}", serverId, e.getMessage());
        }
        // 批量计算能力标签（一次 embedBatch，避免逐工具调用 resolve 产生 N 次 HTTP 请求）
        List<List<String>> allCapabilities = toolCapabilityRegistry.resolveAll(tools);

        List<ToolMeta> metas = new ArrayList<>(tools.size());
        for (int i = 0; i < tools.size(); i++) {
            McpSchema.Tool t = tools.get(i);
            // 持久化到数据库
            try {
                mcpToolRepository.save(McpTool.builder()
                        .serverId(serverUuid)
                        .name(t.name())
                        .title(t.title())
                        .description(t.description())
                        .inputSchema(t.inputSchema() != null
                                ? objectMapper.convertValue(t.inputSchema(), Map.class)
                                : null)
                        .outputSchema(t.outputSchema() != null
                                ? objectMapper.convertValue(t.outputSchema(), Map.class)
                                : null)
                        .annotations(t.annotations() != null
                                ? objectMapper.convertValue(t.annotations(), Map.class)
                                : null)
                        .meta(t.meta())
                        .build());
            } catch (Exception e) {
                log.warn("持久化 MCP 工具失败: {}, {}", t.name(), e.getMessage());
            }
            // 构建内存索引
            metas.add(ToolMeta.builder()
                    .toolName(t.name())
                    .title(t.title())
                    .description(t.description() != null ? t.description() : "")
                    .serverId(serverId)
                    .serverName(serverName)
                    .capabilities(i < allCapabilities.size() ? allCapabilities.get(i) : List.of())
                    .inputSchema(t.inputSchema() != null
                            ? objectMapper.convertValue(t.inputSchema(), Map.class)
                            : null)
                    .build());
        }
        serverTools.put(serverId, new ServerEntry(serverName, metas, true));
        connectedServers.add(serverId);
        log.info("ToolIndex 索引 MCP 服务器: {} ({} 个工具)", serverName, metas.size());
    }

    /**
     * 标记服务器已断开。保留工具定义在内存中，LLM 仍可见但标注离线。
     */
    public void removeServer(String serverId) {
        connectedServers.remove(serverId);
        ServerEntry entry = serverTools.get(serverId);
        if (entry != null) {
            serverTools.put(serverId, new ServerEntry(entry.serverName, entry.tools, false));
            log.debug("ToolIndex 标记 MCP 服务器离线: {}", entry.serverName);
        }
    }

    /**
     * 彻底移除服务器及其工具（配置被删除时调用）。
     */
    public void deleteServer(String serverId) {
        serverTools.remove(serverId);
        connectedServers.remove(serverId);
    }

    /**
     * 判断指定服务器是否已连接。
     */
    public boolean isServerConnected(String serverId) {
        return connectedServers.contains(serverId);
    }

    /**
     * 获取所有索引的工具（含离线服务器）。
     */
    public List<ToolMeta> getAllTools() {
        return serverTools.values().stream()
                .flatMap(e -> e.tools.stream())
                .toList();
    }

    /**
     * 获取所有已连接服务器的工具。
     */
    public List<ToolMeta> getConnectedTools() {
        return serverTools.entrySet().stream()
                .filter(e -> connectedServers.contains(e.getKey()))
                .flatMap(e -> e.getValue().tools.stream())
                .toList();
    }

    /**
     * 根据工具名称反向查找所属服务器 ID。
     */
    public String findServerIdByToolName(String toolName) {
        if (toolName == null) return null;
        for (var entry : serverTools.entrySet()) {
            for (ToolMeta meta : entry.getValue().tools) {
                if (toolName.equals(meta.getToolName())) {
                    return entry.getKey();
                }
            }
        }
        return null;
    }

    /**
     * 获取指定服务器的工具列表。
     */
    public List<ToolMeta> getToolsByServer(String serverId) {
        ServerEntry entry = serverTools.get(serverId);
        return entry != null ? entry.tools : Collections.emptyList();
    }

    private ToolMeta toToolMeta(McpTool t) {
        return ToolMeta.builder()
                .toolName(t.getName())
                .title(t.getTitle())
                .description(t.getDescription() != null ? t.getDescription() : "")
                .serverId(t.getServerId().toString())
                .serverName(t.getServerId().toString())
                .inputSchema(t.getInputSchema())
                .capabilities(List.of())
                .build();
    }

    private record ServerEntry(String serverName, List<ToolMeta> tools, boolean connected) {}
}
