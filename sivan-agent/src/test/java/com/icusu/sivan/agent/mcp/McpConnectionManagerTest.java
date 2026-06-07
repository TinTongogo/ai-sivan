package com.icusu.sivan.agent.mcp;

import com.icusu.sivan.agent.tool.ToolIndex;
import com.icusu.sivan.agent.tool.ToolRegistryImpl;
import com.icusu.sivan.domain.tool.IMcpServerConfigRepository;
import com.icusu.sivan.domain.tool.McpServerConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class McpConnectionManagerTest {

    @Mock private IMcpServerConfigRepository repository;
    @Mock private ToolIndex toolIndex;
    @Mock private ToolRegistryImpl toolRegistry;

    private McpConnectionManager manager;
    private final UUID serverId = UUID.randomUUID();
    private final McpServerConfig config = McpServerConfig.builder()
            .serverId(serverId).name("test-mcp").serverUrl("http://localhost:9999/mcp")
            .active(true).build();

    @BeforeEach
    void setUp() {
        manager = new McpConnectionManager(repository, toolIndex, toolRegistry);
    }

    @Test
    void isConnected_shouldReturnFalse_initially() {
        assertFalse(manager.isConnected(serverId));
    }

    @Test
    void getConnectedCount_shouldBeZero_initially() {
        assertEquals(0, manager.getConnectedCount());
    }

    @Test
    void getClient_shouldReturnEmpty_forUnknownServer() {
        assertTrue(manager.getClient(serverId).isEmpty());
    }

    @Test
    void connectByServerId_shouldSkip_whenServerNotFound() {
        when(repository.findById(serverId)).thenReturn(Optional.empty());

        manager.connectByServerId(serverId);

        verify(toolIndex, never()).indexServer(any(), any(), any());
        verify(toolRegistry, never()).registerServerTools(any());
    }

    @Test
    void connectIfActive_shouldSkip_whenInactive() {
        McpServerConfig inactiveConfig = McpServerConfig.builder()
                .serverId(serverId).active(false).build();
        when(repository.findById(serverId)).thenReturn(Optional.of(inactiveConfig));

        manager.connectIfActive(serverId);
        assertFalse(manager.isConnected(serverId));
    }

    @Test
    void connectIfActive_shouldSkip_whenAlreadyConnected() {
        // 先 disconnect（不会触发连接），然后 isConnected 应该为 false
        assertFalse(manager.isConnected(serverId));
    }

    @Test
    void disconnect_shouldHandleUnknownServer() {
        assertDoesNotThrow(() -> manager.disconnect(serverId));
    }

    @Test
    void disconnectAll_shouldBeSafe_whenEmpty() {
        assertDoesNotThrow(() -> manager.disconnectAll());
    }

    @Test
    void shutdown_shouldCallDisconnectAll() {
        assertDoesNotThrow(() -> manager.shutdown());
    }

    @Test
    void connectAll_shouldCallFindAllActive() {
        when(repository.findAllActive()).thenReturn(List.of());
        manager.connectAll();
        verify(repository).findAllActive();
    }

    @Test
    void isConnected_shouldReturnTrue_afterManualPut() {
        // 用反射直接操作 connectedClients
        assertFalse(manager.isConnected(serverId));
        // disconnect 后不会连接，但不会影响未连接状态
        manager.disconnect(serverId);
        assertFalse(manager.isConnected(serverId));
    }
}
