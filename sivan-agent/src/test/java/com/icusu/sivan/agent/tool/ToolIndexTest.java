package com.icusu.sivan.agent.tool;

import com.icusu.sivan.domain.tool.ToolMeta;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ToolIndexTest {

    @Mock
    private ToolCapabilityRegistry toolCapabilityRegistry;

    private ToolIndex toolIndex;

    @BeforeEach
    void setUp() {
        toolIndex = new ToolIndex(toolCapabilityRegistry);
    }

    private static McpSchema.Tool mcpTool(String name, String desc) {
        return McpSchema.Tool.builder()
                .name(name)
                .description(desc)
                .build();
    }

    @Test
    void indexServer_索引工具() {
        String serverId = UUID.randomUUID().toString();

        when(toolCapabilityRegistry.resolveAll(any())).thenReturn(List.of(List.of("utility")));

        toolIndex.indexServer(serverId, "test-server", List.of(mcpTool("tool-a", "一个测试工具")));

        assertEquals(1, toolIndex.getAllTools().size());
        assertEquals("tool-a", toolIndex.getAllTools().get(0).getToolName());
        assertTrue(toolIndex.isServerConnected(serverId));
    }

    @Test
    void indexServer_多个工具() {
        String serverId = UUID.randomUUID().toString();

        when(toolCapabilityRegistry.resolveAll(any()))
                .thenReturn(List.of(List.of("code"), List.of("search")));

        toolIndex.indexServer(serverId, "multi", List.of(
                mcpTool("t1", "工具一"), mcpTool("t2", "工具二")));

        assertEquals(2, toolIndex.getAllTools().size());
    }

    @Test
    void removeServer_标记离线但保留工具() {
        String serverId = UUID.randomUUID().toString();

        when(toolCapabilityRegistry.resolveAll(any())).thenReturn(List.of(List.of()));

        toolIndex.indexServer(serverId, "test", List.of(mcpTool("keep-tool", "离线后仍可见")));
        assertEquals(1, toolIndex.getConnectedTools().size());

        toolIndex.removeServer(serverId);
        assertEquals(1, toolIndex.getAllTools().size());
        assertFalse(toolIndex.isServerConnected(serverId));
        assertEquals(0, toolIndex.getConnectedTools().size());
    }

    @Test
    void deleteServer_彻底移除() {
        String serverId = UUID.randomUUID().toString();

        when(toolCapabilityRegistry.resolveAll(any())).thenReturn(List.of(List.of()));

        toolIndex.indexServer(serverId, "test", List.of(mcpTool("gone-tool", "")));
        assertEquals(1, toolIndex.getAllTools().size());

        toolIndex.deleteServer(serverId);
        assertTrue(toolIndex.getAllTools().isEmpty());
        assertFalse(toolIndex.isServerConnected(serverId));
    }

    @Test
    void findServerIdByToolName_正向查找() {
        String serverId = UUID.randomUUID().toString();

        when(toolCapabilityRegistry.resolveAll(any())).thenReturn(List.of(List.of()));

        toolIndex.indexServer(serverId, "srv", List.of(mcpTool("named-tool", "测试")));

        assertEquals(serverId, toolIndex.findServerIdByToolName("named-tool"));
        assertNull(toolIndex.findServerIdByToolName("nonexistent"));
        assertNull(toolIndex.findServerIdByToolName(null));
    }

    @Test
    void getToolsByServer_按服务器查询() {
        UUID serverId = UUID.randomUUID();

        when(toolCapabilityRegistry.resolveAll(any())).thenReturn(List.of(List.of()));

        toolIndex.indexServer(serverId.toString(), "srv", List.of(mcpTool("srv-tool", "")));

        assertEquals(1, toolIndex.getToolsByServer(serverId.toString()).size());
        assertTrue(toolIndex.getToolsByServer("unknown").isEmpty());
    }

    @Test
    void indexServer_能力注册失败不影响索引() {
        String serverId = UUID.randomUUID().toString();

        when(toolCapabilityRegistry.resolveAll(any()))
                .thenThrow(new RuntimeException("能力注册不可用"));

        toolIndex.indexServer(serverId, "srv", List.of(mcpTool("mem-only", "仅内存")));

        assertEquals(1, toolIndex.getAllTools().size());
        assertEquals("mem-only", toolIndex.getAllTools().get(0).getToolName());
    }
}
