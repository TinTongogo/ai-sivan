package com.icusu.sivan.agent.tool;

import com.icusu.sivan.agent.mcp.McpConnectionManager;
import com.icusu.sivan.agent.mcp.McpClientWrapper;
import com.icusu.sivan.core.tool.ToolExecutor;
import com.icusu.sivan.core.tool.ToolRegistry;
import com.icusu.sivan.core.tool.ToolResult;
import com.icusu.sivan.core.tool.ToolSpec;
import com.icusu.sivan.domain.tool.ToolMeta;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link ToolRegistry} 内存实现。
 */
@Slf4j
@Component
public class ToolRegistryImpl implements ToolRegistry {

    private final Map<String, Entry> registry = new ConcurrentHashMap<>();
    private final McpConnectionManager connectionManager;
    private final ToolIndex toolIndex;

    public ToolRegistryImpl(@Lazy McpConnectionManager connectionManager, ToolIndex toolIndex) {
        this.connectionManager = connectionManager;
        this.toolIndex = toolIndex;
    }

    @PostConstruct
    void init() {
        log.info("ToolRegistryImpl 初始化完成");
    }

    @Override
    public void register(ToolSpec spec, ToolExecutor executor) {
        registry.put(spec.name(), new Entry(spec, executor));
    }

    @Override
    public ToolExecutor find(String name) {
        Entry entry = registry.get(name);
        return entry != null ? entry.executor : null;
    }

    @Override
    public List<ToolSpec> allSpecs() {
        return registry.values().stream().map(Entry::spec).toList();
    }

    @Override
    public void unregister(String name) {
        registry.remove(name);
    }

    /**
     * 注册某个 MCP 服务器的所有工具。
     * 由 {@link McpConnectionManager} 在连接新服务器后调用。
     */
    public void registerServerTools(String serverId) {
        List<ToolMeta> tools = toolIndex.getToolsByServer(serverId);
        for (ToolMeta meta : tools) {
            if (meta.getInputSchema() == null) continue;
            ToolSpec spec = new ToolSpec(meta.getToolName(), meta.getDescription(), meta.getInputSchema());
            registry.put(spec.name(), new Entry(spec, (call, ctx) -> {
                UUID sid;
                try {
                    sid = UUID.fromString(meta.getServerId());
                } catch (Exception e) {
                    return Mono.just(ToolResult.failure(spec.name(), "无效的服务器 ID"));
                }
                McpClientWrapper client = connectionManager.getClient(sid).orElse(null);
                if (client == null) {
                    return Mono.just(ToolResult.failure(spec.name(), "服务器未连接"));
                }
                try {
                    McpSchema.CallToolRequest request = new McpSchema.CallToolRequest(
                            spec.name(), call.args() != null ? call.args() : Map.of());
                    McpSchema.CallToolResult result = client.callTool(request);
                    boolean success = !Boolean.TRUE.equals(result.isError());
                    String output = extractText(result);
                    return Mono.just(new ToolResult(spec.name(), success, output));
                } catch (Exception e) {
                    return Mono.just(ToolResult.failure(spec.name(), e.getMessage()));
                }
            }));
        }
        if (!tools.isEmpty()) {
            log.info("ToolRegistry 已注册服务器 {} 的 {} 个工具", serverId, tools.size());
        }
    }

    /** 移除某个 MCP 服务器的所有工具。 */
    public void unregisterServerTools(String serverId) {
        List<ToolMeta> tools = toolIndex.getToolsByServer(serverId);
        for (ToolMeta meta : tools) {
            registry.remove(meta.getToolName());
        }
        if (!tools.isEmpty()) {
            log.info("ToolRegistry 已注销服务器 {} 的 {} 个工具", serverId, tools.size());
        }
    }

    private static String extractText(McpSchema.CallToolResult result) {
        if (result == null || result.content() == null) return "";
        StringBuilder sb = new StringBuilder();
        for (McpSchema.Content content : result.content()) {
            if (content instanceof McpSchema.TextContent tc) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(tc.text());
            }
        }
        return sb.toString();
    }

    private record Entry(ToolSpec spec, ToolExecutor executor) {}
}
