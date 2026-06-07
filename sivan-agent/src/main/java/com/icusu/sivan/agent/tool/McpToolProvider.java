package com.icusu.sivan.agent.tool;

import com.icusu.sivan.core.message.Content;
import com.icusu.sivan.agent.mcp.McpConnectionManager;
import com.icusu.sivan.agent.mcp.McpClientWrapper;
import com.icusu.sivan.core.tool.ToolExecutor;
import lombok.extern.slf4j.Slf4j;
import com.icusu.sivan.core.tool.ToolProvider;
import com.icusu.sivan.core.tool.ToolResult;
import com.icusu.sivan.core.tool.ToolSpec;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@link ToolProvider} 端口实现 — 包装 MCP SDK 同步客户端。
 * <p>
 * 工具发现和执行的优先级：
 * <ol>
 *   <li>外部 MCP 服务器工具（通过 {@link ToolIndex} 查找）</li>
 *   <li>内部注册工具（通过 {@link ToolRegistryImpl} 查找，如 file_read/file_write 等）</li>
 * </ol>
 */
@Slf4j
@Component
public class McpToolProvider implements ToolProvider {

    private final McpConnectionManager connectionManager;
    private final ToolIndex toolIndex;
    private final ToolRegistryImpl toolRegistry;

    public McpToolProvider(McpConnectionManager connectionManager, ToolIndex toolIndex,
                          ToolRegistryImpl toolRegistry) {
        this.connectionManager = connectionManager;
        this.toolIndex = toolIndex;
        this.toolRegistry = toolRegistry;
    }

    @Override
    public String providerId() {
        return "mcp";
    }

    @Override
    public List<ToolSpec> listTools() {
        List<ToolSpec> tools = new ArrayList<>(toolIndex.getAllTools().stream()
                .filter(t -> t.getInputSchema() != null)
                .map(t -> {
                    String desc = t.getDescription();
                    if (!toolIndex.isServerConnected(t.getServerId())) {
                        desc = "[离线] " + desc;
                    }
                    return new ToolSpec(t.getToolName(), desc, t.getInputSchema());
                })
                .toList());
        // 合并 ToolRegistryImpl 中单独注册的内部工具
        int internalCount = 0;
        for (ToolSpec spec : toolRegistry.allSpecs()) {
            if (tools.stream().noneMatch(t -> t.name().equals(spec.name()))) {
                tools.add(spec);
                internalCount++;
            }
        }
        log.debug("工具列表: MCP={} 内部={}", tools.size() - internalCount, internalCount);
        return tools;
    }

    @Override
    public Mono<ToolResult> execute(String toolName, Map<String, Object> args) {
        // 优先查 ToolIndex（外部 MCP 工具）
        String serverId = toolIndex.findServerIdByToolName(toolName);
        if (serverId != null) {
            if (!toolIndex.isServerConnected(serverId)) {
                return Mono.just(ToolResult.failure(toolName,
                        "工具 " + toolName + " 所属 MCP 服务器未连接，请在设置中启用该服务器"));
            }
            McpClientWrapper client = connectionManager.getClient(UUID.fromString(serverId)).orElse(null);
            if (client != null) {
                return Mono.fromCallable(() -> {
                    Map<String, Object> cleanArgs = args != null ? new HashMap<>(args) : new HashMap<>();
                    // 文件操作类工具需要路径感知，保留 _fileRootPath 注入 MCP 服务器
                    if (toolName.startsWith("file_")) {
                        cleanArgs.putIfAbsent("_fileRootPath",
                                (String) cleanArgs.getOrDefault("_fileRootPath", ""));
                    } else {
                        // 非文件工具剥离内部参数，避免 MCP 服务器校验报错
                        cleanArgs.remove("_fileRootPath");
                        cleanArgs.remove("_archived");
                    }
                    McpSchema.CallToolRequest request = new McpSchema.CallToolRequest(
                            toolName, cleanArgs);
                    McpSchema.CallToolResult result = client.callTool(request);
                    String text = extractText(result);
                    boolean isError = Boolean.TRUE.equals(result.isError());
                    log.info("MCP 工具执行: tool={} args={} isError={} textLen={}", toolName, args, isError, text.length());
                    boolean success = !isError && !text.startsWith("Error:") && !text.startsWith("MCP tool returned");
                    return new ToolResult(toolName, success, text);
                }).subscribeOn(Schedulers.boundedElastic())
                .timeout(java.time.Duration.ofSeconds(25))
                .onErrorResume(e -> {
                    log.warn("MCP 工具调用异常: tool={} error={}", toolName, e.getMessage());
                    return Mono.just(ToolResult.failure(toolName, "MCP 工具调用失败: " + e.getMessage()));
                });
            }
        }

        // 回退到 ToolRegistryImpl（内部工具如 file_read/file_write）
        ToolExecutor executor = toolRegistry.find(toolName);
        if (executor != null) {
//            log.info("内部工具执行: tool={} args={}", toolName, args);
            return executor.execute(
                    new Content.ToolCall("internal", toolName,
                            args != null ? args : Map.of()),
                    null)
                    .doOnNext(result -> log.info("内部工具执行结果: tool={} success={} outputLen={}",
                            toolName, result.success(), result.output() != null ? result.output().length() : 0))
                    .doOnError(e -> log.error("内部工具执行异常: tool={} error={}", toolName, e.getMessage()));
        }

        return Mono.just(ToolResult.failure(toolName, "未找到工具: " + toolName));
    }

    /** 从 CallToolResult 中提取文本内容。 */
    private String extractText(McpSchema.CallToolResult result) {
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
}
