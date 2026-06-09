package com.icusu.sivan.agent.tool;

import com.icusu.sivan.domain.tool.ToolMeta;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

/**
 * MCP 工具索引，维护 serverId → 工具列表的内存映射。
 * <p>
 * 运行时由 {@link com.icusu.sivan.agent.mcp.McpConnectionManager}
 * 在连接/断开时更新连接状态。断连后工具定义保留，LLM 仍可见但标注离线。
 * 不再持久化到 DB。
 */
@Slf4j
@Component
public class ToolIndex {

    private final ConcurrentMap<String, ServerEntry> serverTools = new ConcurrentHashMap<>();
    private final Set<String> connectedServers = ConcurrentHashMap.newKeySet();
    private final ToolCapabilityRegistry toolCapabilityRegistry;
    private final ObjectMapper objectMapper;

    public ToolIndex(ToolCapabilityRegistry toolCapabilityRegistry) {
        this.toolCapabilityRegistry = toolCapabilityRegistry;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 索引一个 MCP 服务器的工具列表（连接成功后调用）。
     */
    public void indexServer(String serverId, String serverName, List<McpSchema.Tool> tools) {
        List<List<String>> allCapabilities;
        try {
            allCapabilities = toolCapabilityRegistry.resolveAll(tools);
        } catch (Exception e) {
            log.warn("工具能力注册失败，降级为空标签: {}", e.getMessage());
            allCapabilities = tools.stream().map(t -> List.<String>of()).toList();
        }

        List<ToolMeta> metas = new ArrayList<>(tools.size());
        for (int i = 0; i < tools.size(); i++) {
            McpSchema.Tool t = tools.get(i);
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

    private record ServerEntry(String serverName, List<ToolMeta> tools, boolean connected) {}
}
