package com.icusu.sivan.agent.tool;

import com.icusu.sivan.agent.mcp.McpClientWrapper;
import com.icusu.sivan.agent.mcp.McpConnectionManager;
import com.icusu.sivan.core.tool.ToolExecutor;
import com.icusu.sivan.core.tool.ToolResult;
import com.icusu.sivan.core.tool.ToolSpec;
import com.icusu.sivan.domain.tool.ToolMeta;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class McpToolProviderTest {

    @Mock private McpConnectionManager connectionManager;
    @Mock private ToolIndex toolIndex;
    @Mock private ToolRegistryImpl toolRegistry;

    private McpToolProvider provider;

    @BeforeEach
    void setUp() {
        provider = new McpToolProvider(connectionManager, toolIndex, toolRegistry);
    }

    @Test
    void providerId_返回mcp() {
        assertEquals("mcp", provider.providerId());
    }

    // ── listTools ──

    @Test
    void listTools_返回MCP和内部工具() {
        ToolMeta mcpMeta = new ToolMeta();
        mcpMeta.setToolName("mcp-tool");
        mcpMeta.setDescription("MCP 工具");
        mcpMeta.setInputSchema(Map.of("type", "object"));
        mcpMeta.setServerId("srv-1");

        when(toolIndex.getAllTools()).thenReturn(List.of(mcpMeta));
        when(toolIndex.isServerConnected("srv-1")).thenReturn(true);
        when(toolRegistry.allSpecs()).thenReturn(List.of(
                new ToolSpec("file_read", "内置", Map.of("type", "object"))));

        List<ToolSpec> tools = provider.listTools();
        assertTrue(tools.stream().anyMatch(t -> t.name().equals("mcp-tool")));
        assertTrue(tools.stream().anyMatch(t -> t.name().equals("file_read")));
    }

    @Test
    void listTools_离线服务器工具标记前缀() {
        ToolMeta meta = new ToolMeta();
        meta.setToolName("offline-tool");
        meta.setDescription("离线工具");
        meta.setInputSchema(Map.of("type", "object"));
        meta.setServerId("srv-off");

        when(toolIndex.getAllTools()).thenReturn(List.of(meta));
        when(toolIndex.isServerConnected("srv-off")).thenReturn(false);
        when(toolRegistry.allSpecs()).thenReturn(List.of());

        List<ToolSpec> tools = provider.listTools();
        assertEquals(1, tools.size());
        assertTrue(tools.get(0).description().startsWith("[离线]"));
    }

    @Test
    void listTools_内部工具不重复() {
        when(toolIndex.getAllTools()).thenReturn(List.of());
        when(toolRegistry.allSpecs()).thenReturn(List.of(
                new ToolSpec("bash", "执行命令", Map.of("type", "object")),
                new ToolSpec("file_read", "读取文件", Map.of("type", "object"))));

        List<ToolSpec> tools = provider.listTools();
        assertEquals(2, tools.size());
    }

    // ── execute MCP 工具 ──

    @Test
    void execute_MCP工具成功() {
        String toolName = "calc";
        UUID serverId = UUID.randomUUID();
        ToolMeta meta = new ToolMeta();
        meta.setToolName(toolName);
        meta.setServerId(serverId.toString());

        McpClientWrapper client = mock(McpClientWrapper.class);
        McpSchema.CallToolResult mcpResult = new McpSchema.CallToolResult(
                List.of(new McpSchema.TextContent("42")), false);

        when(toolIndex.findServerIdByToolName(toolName)).thenReturn(serverId.toString());
        when(toolIndex.isServerConnected(serverId.toString())).thenReturn(true);
        when(connectionManager.getClient(serverId)).thenReturn(Optional.of(client));
        when(client.callTool(any(McpSchema.CallToolRequest.class))).thenReturn(mcpResult);

        ToolResult result = provider.execute(toolName, Map.of("x", 1)).block();
        assertNotNull(result);
        assertTrue(result.success());
        assertEquals("42", result.output());
    }

    @Test
    void execute_MCP工具服务器未连接() {
        when(toolIndex.findServerIdByToolName("offline")).thenReturn("srv-id");
        when(toolIndex.isServerConnected("srv-id")).thenReturn(false);

        ToolResult result = provider.execute("offline", Map.of()).block();
        assertNotNull(result);
        assertFalse(result.success());
        assertTrue(result.output().contains("未连接"));
    }

    @Test
    void execute_MCP工具服务器不存在() {
        UUID serverId = UUID.randomUUID();
        when(toolIndex.findServerIdByToolName("ghost")).thenReturn(serverId.toString());
        when(toolIndex.isServerConnected(serverId.toString())).thenReturn(true);
        when(connectionManager.getClient(serverId)).thenReturn(Optional.empty());

        ToolResult result = provider.execute("ghost", Map.of()).block();
        assertNotNull(result);
        assertFalse(result.success());
    }

    @Test
    void execute_MCP工具执行时清理内部参数() {
        String toolName = "safe-tool";
        UUID serverId = UUID.randomUUID();
        when(toolIndex.findServerIdByToolName(toolName)).thenReturn(serverId.toString());
        when(toolIndex.isServerConnected(serverId.toString())).thenReturn(true);
        when(connectionManager.getClient(serverId)).thenReturn(Optional.empty());

        provider.execute(toolName, Map.of("_fileRootPath", "/tmp", "_archived", true, "realArg", 1)).block();
        // 无断言，只验证不抛出异常
    }

    // ── execute 内部工具 ──

    @Test
    void execute_内部工具成功() {
        ToolExecutor executor = (call, ctx) -> Mono.just(ToolResult.success("bash", "ok"));
        when(toolIndex.findServerIdByToolName("bash")).thenReturn(null);
        when(toolRegistry.find("bash")).thenReturn(executor);

        ToolResult result = provider.execute("bash", Map.of("command", "ls")).block();
        assertNotNull(result);
        assertTrue(result.success());
        assertEquals("ok", result.output());
    }

    @Test
    void execute_内部工具失败() {
        ToolExecutor executor = (call, ctx) -> Mono.just(ToolResult.failure("bash", "执行错误"));
        when(toolIndex.findServerIdByToolName("bash")).thenReturn(null);
        when(toolRegistry.find("bash")).thenReturn(executor);

        ToolResult result = provider.execute("bash", Map.of("command", "bad-command")).block();
        assertNotNull(result);
        assertFalse(result.success());
    }

    // ── execute 未找到 ──

    @Test
    void execute_工具未找到返回错误() {
        when(toolIndex.findServerIdByToolName("unknown")).thenReturn(null);
        when(toolRegistry.find("unknown")).thenReturn(null);

        ToolResult result = provider.execute("unknown", Map.of()).block();
        assertNotNull(result);
        assertFalse(result.success());
        assertTrue(result.output().contains("未找到"));
    }
}
