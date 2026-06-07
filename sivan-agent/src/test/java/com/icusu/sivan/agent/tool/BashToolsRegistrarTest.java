package com.icusu.sivan.agent.tool;

import com.icusu.sivan.core.message.Content;
import com.icusu.sivan.core.tool.ToolExecutor;
import com.icusu.sivan.core.tool.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BashToolsRegistrarTest {

    @Mock private BashService bashService;
    @Mock private ToolRegistryImpl toolRegistry;

    private BashToolsRegistrar registrar;

    @BeforeEach
    void setUp() {
        registrar = new BashToolsRegistrar(bashService, toolRegistry);
    }

    @Test
    void init_注册bash工具() {
        registrar.init();
        verify(toolRegistry).register(argThat(spec -> spec.name().equals("bash")), any());
    }

    @Test
    void execute_成功执行命令() {
        when(bashService.execute(anyString(), anyString(), anyBoolean())).thenReturn("命令输出");

        registrar.init();
        ToolExecutor exec = captureExecutor();

        ToolResult result = exec.execute(
                new Content.ToolCall("1", "bash", Map.of("command", "ls -la", "_fileRootPath", "/tmp")),
                null).block();

        assertNotNull(result);
        assertTrue(result.success());
        assertEquals("命令输出", result.output());
        verify(bashService).execute("ls -la", "/tmp", false);
    }

    @Test
    void execute_command参数缺失() {
        registrar.init();
        ToolExecutor exec = captureExecutor();

        ToolResult result = exec.execute(
                new Content.ToolCall("1", "bash", Map.of("_fileRootPath", "/tmp")),
                null).block();

        assertNotNull(result);
        assertFalse(result.success());
        assertTrue(result.output().contains("参数缺失"));
        verifyNoInteractions(bashService);
    }

    @Test
    void execute_bash执行异常() {
        when(bashService.execute(anyString(), anyString(), anyBoolean())).thenThrow(new RuntimeException("执行超时"));

        registrar.init();
        ToolExecutor exec = captureExecutor();

        ToolResult result = exec.execute(
                new Content.ToolCall("1", "bash", Map.of("command", "sleep 100", "_fileRootPath", "/tmp")),
                null).block();

        assertNotNull(result);
        assertFalse(result.success());
        assertTrue(result.output().contains("执行超时"));
    }

    private ToolExecutor captureExecutor() {
        var captor = org.mockito.ArgumentCaptor.forClass(ToolExecutor.class);
        verify(toolRegistry).register(argThat(spec -> spec.name().equals("bash")), captor.capture());
        return captor.getValue();
    }
}
