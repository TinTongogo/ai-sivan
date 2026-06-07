package com.icusu.sivan.agent.tool;

import com.icusu.sivan.core.message.Content;
import com.icusu.sivan.core.tool.ToolExecutor;
import com.icusu.sivan.core.tool.ToolResult;
import com.icusu.sivan.core.tool.ToolSpec;
import com.icusu.sivan.infra.file.FileOperationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FileToolsRegistrarTest {

    @Mock
    private FileOperationService fileOperationService;
    @Mock
    private ToolRegistryImpl toolRegistry;

    private FileToolsRegistrar registrar;

    @BeforeEach
    void setUp() {
        registrar = new FileToolsRegistrar(fileOperationService, toolRegistry);
    }

    @Test
    void init_注册四个文件工具() {
        registrar.init();
        verify(toolRegistry, times(4)).register(any(), any());
    }

    // ── file_read ──

    @Test
    void fileRead_成功读取() {
        when(fileOperationService.fileRead(anyString(), any(), anyBoolean(), anyInt(), anyInt()))
                .thenReturn("file content");

        registrar.init();

        ToolExecutor exec = captureExecutor("file_read");
        ToolResult result = exec.execute(
                new Content.ToolCall("1", "file_read", Map.of("rawPath", "test.txt", "_fileRootPath", "/project")),
                null).block();

        assertNotNull(result);
        assertTrue(result.success());
        assertEquals("file content", result.output());
    }

    @Test
    void fileRead_rawPath缺失返回失败() {
        registrar.init();
        ToolExecutor exec = captureExecutor("file_read");
        ToolResult result = exec.execute(
                new Content.ToolCall("1", "file_read", Map.of("_fileRootPath", "/project")), null).block();
        assertNotNull(result);
        assertFalse(result.success());
        assertTrue(result.output().contains("参数缺失"));
        verifyNoInteractions(fileOperationService);
    }

    @Test
    void fileRead_异常返回失败() {
        when(fileOperationService.fileRead(anyString(), any(), anyBoolean(), anyInt(), anyInt()))
                .thenThrow(new RuntimeException("文件不存在"));

        registrar.init();
        ToolExecutor exec = captureExecutor("file_read");
        ToolResult result = exec.execute(
                new Content.ToolCall("1", "file_read", Map.of("rawPath", "missing.txt", "_fileRootPath", "/project")),
                null).block();

        assertNotNull(result);
        assertFalse(result.success());
    }

    @Test
    void fileRead_支持offset和limit() {
        when(fileOperationService.fileRead(anyString(), any(), anyBoolean(), eq(10), eq(50)))
                .thenReturn("partial content");

        registrar.init();
        ToolExecutor exec = captureExecutor("file_read");
        exec.execute(
                new Content.ToolCall("1", "file_read", Map.of("rawPath", "big.txt", "_fileRootPath", "/p", "offset", 10, "limit", 50)),
                null).block();

        verify(fileOperationService).fileRead(eq("big.txt"), any(), anyBoolean(), eq(10), eq(50));
    }

    // ── file_write ──

    @Test
    void fileWrite_成功写入() {
        when(fileOperationService.fileWrite(anyString(), anyString(), any(), anyBoolean()))
                .thenReturn("written");

        registrar.init();
        ToolExecutor exec = captureExecutor("file_write");
        ToolResult result = exec.execute(
                new Content.ToolCall("1", "file_write", Map.of("rawPath", "out.txt", "content", "hello", "_fileRootPath", "/project")),
                null).block();

        assertNotNull(result);
        assertTrue(result.success());
        assertEquals("written", result.output());
    }

    @Test
    void fileWrite_rawPath缺失() {
        registrar.init();
        ToolExecutor exec = captureExecutor("file_write");
        ToolResult result = exec.execute(
                new Content.ToolCall("1", "file_write", Map.of("content", "hello", "_fileRootPath", "/project")), null).block();

        assertNotNull(result);
        assertFalse(result.success());
        assertTrue(result.output().contains("参数缺失"));
    }

    // ── file_list ──

    @Test
    void fileList_成功列出() {
        when(fileOperationService.fileList(anyString(), any(), anyBoolean(), any()))
                .thenReturn(List.of(
                        Map.of("name", "file.txt", "directory", false, "size", 100L),
                        Map.of("name", "subdir", "directory", true)
                ));

        registrar.init();
        ToolExecutor exec = captureExecutor("file_list");
        ToolResult result = exec.execute(
                new Content.ToolCall("1", "file_list", Map.of("rawPath", ".", "_fileRootPath", "/project")),
                null).block();

        assertNotNull(result);
        assertTrue(result.success());
        assertTrue(result.output().contains("[FILE] file.txt (100 B)"));
        assertTrue(result.output().contains("[DIR] subdir"));
    }

    @Test
    void fileList_rawPath缺失() {
        registrar.init();
        ToolExecutor exec = captureExecutor("file_list");
        ToolResult result = exec.execute(
                new Content.ToolCall("1", "file_list", Map.of("_fileRootPath", "/project")), null).block();
        assertNotNull(result);
        assertFalse(result.success());
    }

    @Test
    void fileList_支持pattern过滤() {
        when(fileOperationService.fileList(anyString(), any(), anyBoolean(), eq("*.java")))
                .thenReturn(List.of());

        registrar.init();
        ToolExecutor exec = captureExecutor("file_list");
        exec.execute(
                new Content.ToolCall("1", "file_list", Map.of("rawPath", "src", "_fileRootPath", "/project", "pattern", "*.java")),
                null).block();

        verify(fileOperationService).fileList(anyString(), any(), anyBoolean(), eq("*.java"));
    }

    // ── file_search ──

    @Test
    void fileSearch_成功搜索() {
        when(fileOperationService.fileSearch(anyString(), any(), anyBoolean(), anyString(), any(), anyInt()))
                .thenReturn(List.of(
                        Map.of("file", "test.java", "line", 10, "content", "public class")
                ));

        registrar.init();
        ToolExecutor exec = captureExecutor("file_search");
        ToolResult result = exec.execute(
                new Content.ToolCall("1", "file_search", Map.of("rawPath", "src", "searchPattern", "public", "_fileRootPath", "/project")),
                null).block();

        assertNotNull(result);
        assertTrue(result.success());
        assertTrue(result.output().contains("找到 1 个匹配"));
        assertTrue(result.output().contains("test.java:10"));
    }

    @Test
    void fileSearch_rawPath缺失() {
        registrar.init();
        ToolExecutor exec = captureExecutor("file_search");
        ToolResult result = exec.execute(
                new Content.ToolCall("1", "file_search", Map.of("searchPattern", "test", "_fileRootPath", "/project")), null).block();
        assertNotNull(result);
        assertFalse(result.success());
    }

    @Test
    void fileSearch_searchPattern缺失() {
        registrar.init();
        ToolExecutor exec = captureExecutor("file_search");
        ToolResult result = exec.execute(
                new Content.ToolCall("1", "file_search", Map.of("rawPath", ".", "_fileRootPath", "/project")), null).block();
        assertNotNull(result);
        assertFalse(result.success());
        assertTrue(result.output().contains("参数缺失"));
    }

    // ── 辅助方法 ──

    /** 捕获 toolRegistry.register 调用中指定名称的工具的 executor。 */
    private ToolExecutor captureExecutor(String toolName) {
        var captor = org.mockito.ArgumentCaptor.forClass(ToolExecutor.class);
        verify(toolRegistry).register(
                argThat(spec -> ((ToolSpec) spec).name().equals(toolName)),
                captor.capture());
        return captor.getValue();
    }
}
