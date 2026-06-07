package com.icusu.sivan.agent.tool;

import com.icusu.sivan.agent.mcp.McpConnectionManager;
import com.icusu.sivan.agent.mcp.McpClientWrapper;
import com.icusu.sivan.core.message.Content;
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
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ToolRegistryImplTest {

    @Mock
    private McpConnectionManager connectionManager;
    @Mock
    private ToolIndex toolIndex;

    private ToolRegistryImpl registry;

    @BeforeEach
    void setUp() {
        registry = new ToolRegistryImpl(connectionManager, toolIndex);
    }

    @Test
    void register_注册后可查找() {
        ToolSpec spec = new ToolSpec("test-tool", "测试工具", Map.of("type", "object"));
        ToolExecutor executor = (call, ctx) -> Mono.just(ToolResult.success("test-tool", "ok"));
        registry.register(spec, executor);

        assertNotNull(registry.find("test-tool"));
        assertNull(registry.find("nonexistent"));
    }

    @Test
    void allSpecs_返回所有注册规格() {
        registry.register(new ToolSpec("a", "desc a", Map.of()), (c, ctx) -> Mono.just(ToolResult.success("a", "")));
        registry.register(new ToolSpec("b", "desc b", Map.of()), (c, ctx) -> Mono.just(ToolResult.success("b", "")));

        List<ToolSpec> specs = registry.allSpecs();
        assertEquals(2, specs.size());
        assertTrue(specs.stream().anyMatch(s -> s.name().equals("a")));
        assertTrue(specs.stream().anyMatch(s -> s.name().equals("b")));
    }

    @Test
    void unregister_移除工具() {
        ToolSpec spec = new ToolSpec("tmp", "临时", Map.of());
        registry.register(spec, (c, ctx) -> Mono.just(ToolResult.success("tmp", "")));
        assertNotNull(registry.find("tmp"));

        registry.unregister("tmp");
        assertNull(registry.find("tmp"));
    }

    @Test
    void registerServerTools_注册MCP服务器工具() {
        UUID serverId = UUID.randomUUID();
        ToolMeta meta = new ToolMeta();
        meta.setToolName("mcp-tool");
        meta.setDescription("MCP 工具");
        meta.setServerId(serverId.toString());
        meta.setInputSchema(Map.of("type", "object"));

        when(toolIndex.getToolsByServer(serverId.toString())).thenReturn(List.of(meta));

        registry.registerServerTools(serverId.toString());

        assertNotNull(registry.find("mcp-tool"));
        verify(toolIndex).getToolsByServer(serverId.toString());
    }

    @Test
    void registerServerTools_nullSchema跳过() {
        ToolMeta meta = new ToolMeta();
        meta.setToolName("bad-tool");
        meta.setInputSchema(null);
        meta.setServerId(UUID.randomUUID().toString());

        when(toolIndex.getToolsByServer(anyString())).thenReturn(List.of(meta));

        registry.registerServerTools("srv-id");

        assertNull(registry.find("bad-tool"));
    }

    @Test
    void unregisterServerTools_移除服务器全部工具() {
        UUID serverId = UUID.randomUUID();
        ToolMeta meta = new ToolMeta();
        meta.setToolName("mcp-tool");
        meta.setServerId(serverId.toString());
        meta.setInputSchema(Map.of("type", "object"));

        when(toolIndex.getToolsByServer(serverId.toString())).thenReturn(List.of(meta));

        registry.registerServerTools(serverId.toString());
        assertNotNull(registry.find("mcp-tool"));

        registry.unregisterServerTools(serverId.toString());
        assertNull(registry.find("mcp-tool"));
    }

    @Test
    void registerServerTools_执行工具成功() {
        UUID serverId = UUID.randomUUID();
        ToolMeta meta = new ToolMeta();
        meta.setToolName("exec-tool");
        meta.setServerId(serverId.toString());
        meta.setInputSchema(Map.of("type", "object"));

        McpClientWrapper client = mock(McpClientWrapper.class);
        McpSchema.CallToolResult mcpResult = new McpSchema.CallToolResult(
                List.of(new McpSchema.TextContent("执行成功")), false);

        when(toolIndex.getToolsByServer(serverId.toString())).thenReturn(List.of(meta));
        when(connectionManager.getClient(serverId)).thenReturn(Optional.of(client));
        when(client.callTool(any(McpSchema.CallToolRequest.class))).thenReturn(mcpResult);

        registry.registerServerTools(serverId.toString());

        ToolExecutor executor = registry.find("exec-tool");
        assertNotNull(executor);
        ToolResult result = executor.execute(
                new Content.ToolCall("1", "exec-tool", Map.of()), null).block();
        assertNotNull(result);
        assertTrue(result.success());
        assertEquals("执行成功", result.output());
    }

    @Test
    void registerServerTools_执行工具失败() {
        UUID serverId = UUID.randomUUID();
        ToolMeta meta = new ToolMeta();
        meta.setToolName("fail-tool");
        meta.setServerId(serverId.toString());
        meta.setInputSchema(Map.of("type", "object"));

        when(toolIndex.getToolsByServer(serverId.toString())).thenReturn(List.of(meta));
        when(connectionManager.getClient(serverId)).thenReturn(Optional.empty());

        registry.registerServerTools(serverId.toString());

        ToolExecutor executor = registry.find("fail-tool");
        ToolResult result = executor.execute(
                new Content.ToolCall("1", "fail-tool", Map.of()), null).block();
        assertNotNull(result);
        assertFalse(result.success());
        assertTrue(result.output().contains("未连接"));
    }

    @Test
    void registerServerTools_执行时客户端异常() {
        UUID serverId = UUID.randomUUID();
        ToolMeta meta = new ToolMeta();
        meta.setToolName("crash-tool");
        meta.setServerId(serverId.toString());
        meta.setInputSchema(Map.of("type", "object"));

        McpClientWrapper client = mock(McpClientWrapper.class);

        when(toolIndex.getToolsByServer(serverId.toString())).thenReturn(List.of(meta));
        when(connectionManager.getClient(serverId)).thenReturn(Optional.of(client));
        when(client.callTool(any())).thenThrow(new RuntimeException("连接断开"));

        registry.registerServerTools(serverId.toString());

        ToolExecutor executor = registry.find("crash-tool");
        ToolResult result = executor.execute(
                new Content.ToolCall("1", "crash-tool", Map.of()), null).block();
        assertNotNull(result);
        assertFalse(result.success());
        assertTrue(result.output().contains("连接断开"));
    }

    @Test
    void registerServerTools_无效服务器ID返回失败() {
        ToolMeta meta = new ToolMeta();
        meta.setToolName("bad-id-tool");
        meta.setServerId("not-a-uuid");
        meta.setInputSchema(Map.of("type", "object"));

        when(toolIndex.getToolsByServer("not-a-uuid")).thenReturn(List.of(meta));

        registry.registerServerTools("not-a-uuid");

        ToolExecutor executor = registry.find("bad-id-tool");
        ToolResult result = executor.execute(
                new Content.ToolCall("1", "bad-id-tool", Map.of()), null).block();
        assertNotNull(result);
        assertFalse(result.success());
        assertTrue(result.output().contains("无效的服务器 ID"));
    }

    @Test
    void registerServerTools_空工具列表不报错() {
        when(toolIndex.getToolsByServer("empty-srv")).thenReturn(List.of());
        registry.registerServerTools("empty-srv");
        verify(toolIndex).getToolsByServer("empty-srv");
    }

    @Test
    void 重复注册覆盖() {
        ToolSpec spec = new ToolSpec("override", "v1", Map.of());
        registry.register(spec, (c, ctx) -> Mono.just(ToolResult.success("override", "v1")));

        ToolSpec spec2 = new ToolSpec("override", "v2", Map.of());
        registry.register(spec2, (c, ctx) -> Mono.just(ToolResult.success("override", "v2")));

        ToolExecutor exec = registry.find("override");
        ToolResult result = exec.execute(null, null).block();
        assertEquals("v2", result.output());
    }
}
