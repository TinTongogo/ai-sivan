package com.icusu.sivan.infra.file;

import com.icusu.sivan.common.exception.DomainException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FileOperationServiceTest {

    @Mock
    private FileSecurityManager securityManager;

    @Mock
    private DocumentTextExtractor textExtractor;

    private FileOperationService fileOp;

    private static final String FILE_ROOT = "/tmp/sivan/acct-1/proj-a";
    private static final boolean NOT_ARCHIVED = false;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        fileOp = new FileOperationService(securityManager, textExtractor);
        ReflectionTestUtils.setField(fileOp, "maxFileSize", 1048576L);
    }

    @Test
    void fileRead_shouldReturnContent() throws Exception {
        Path file = tempDir.resolve("hello.txt");
        Files.writeString(file, "line1\nline2\nline3");
        when(securityManager.validate(eq(file.toString()), eq(FILE_ROOT), eq(NOT_ARCHIVED), eq(FileSecurityManager.FileOperation.READ)))
                .thenReturn(file);

        String content = fileOp.fileRead(file.toString(), FILE_ROOT, NOT_ARCHIVED, 0, 2000);
        assertTrue(content.contains("line1"));
    }

    @Test
    void fileRead_shouldRespectOffsetAndLimit() throws Exception {
        Path file = tempDir.resolve("lines.txt");
        Files.writeString(file, "a\nb\nc\nd\ne");
        when(securityManager.validate(eq(file.toString()), eq(FILE_ROOT), eq(NOT_ARCHIVED), eq(FileSecurityManager.FileOperation.READ)))
                .thenReturn(file);

        String content = fileOp.fileRead(file.toString(), FILE_ROOT, NOT_ARCHIVED, 1, 2);
        assertFalse(content.contains("a"));
        assertTrue(content.contains("b"));
        assertTrue(content.contains("c"));
        assertFalse(content.contains("d"));
    }

    @Test
    void fileRead_shouldThrowWhenFileNotFound() {
        String path = tempDir.resolve("nonexistent.txt").toString();
        when(securityManager.validate(eq(path), eq(FILE_ROOT), eq(NOT_ARCHIVED), eq(FileSecurityManager.FileOperation.READ)))
                .thenReturn(Path.of(path));

        assertThrows(DomainException.class, () ->
                fileOp.fileRead(path, FILE_ROOT, NOT_ARCHIVED, 0, 100));
    }

    @Test
    void fileList_shouldReturnEntries() throws Exception {
        Path dir = tempDir.resolve("listdir");
        Files.createDirectory(dir);
        Files.writeString(dir.resolve("a.txt"), "a");
        Files.writeString(dir.resolve("b.txt"), "b");
        Files.createDirectory(dir.resolve("subdir"));
        when(securityManager.validate(eq(dir.toString()), eq(FILE_ROOT), eq(NOT_ARCHIVED), eq(FileSecurityManager.FileOperation.READ)))
                .thenReturn(dir);

        List<Map<String, Object>> entries = fileOp.fileList(dir.toString(), FILE_ROOT, NOT_ARCHIVED, null);
        assertEquals(3, entries.size());
    }

    @Test
    void fileList_shouldSupportGlobPattern() throws Exception {
        Path dir = tempDir.resolve("globdir");
        Files.createDirectory(dir);
        Files.writeString(dir.resolve("readme.md"), "md");
        Files.writeString(dir.resolve("config.yml"), "yml");
        when(securityManager.validate(eq(dir.toString()), eq(FILE_ROOT), eq(NOT_ARCHIVED), eq(FileSecurityManager.FileOperation.READ)))
                .thenReturn(dir);

        List<Map<String, Object>> entries = fileOp.fileList(dir.toString(), FILE_ROOT, NOT_ARCHIVED, "*.md");
        assertEquals(1, entries.size());
        assertEquals("readme.md", entries.get(0).get("name"));
    }

    @Test
    void fileSearch_shouldFindMatches() throws Exception {
        Path dir = tempDir.resolve("searchdir");
        Files.createDirectory(dir);
        Files.writeString(dir.resolve("a.txt"), "hello world\nfoo bar");
        Files.writeString(dir.resolve("b.txt"), "goodbye\nhello again");
        when(securityManager.validate(eq(dir.toString()), eq(FILE_ROOT), eq(NOT_ARCHIVED), eq(FileSecurityManager.FileOperation.READ)))
                .thenReturn(dir);

        List<Map<String, Object>> results = fileOp.fileSearch(dir.toString(), FILE_ROOT, NOT_ARCHIVED, "hello", null, 0);
        assertEquals(2, results.size());
    }

    @Test
    void fileSearch_shouldThrowForInvalidRegex() {
        when(securityManager.validate(anyString(), eq(FILE_ROOT), eq(NOT_ARCHIVED), eq(FileSecurityManager.FileOperation.READ)))
                .thenReturn(tempDir);

        assertThrows(DomainException.class, () ->
                fileOp.fileSearch(tempDir.toString(), FILE_ROOT, NOT_ARCHIVED, "[invalid", null, 0));
    }

    @Test
    void fileWrite_shouldCreateFile() throws Exception {
        Path file = tempDir.resolve("newfile.txt");
        when(securityManager.validate(eq(file.toString()), eq(FILE_ROOT), eq(NOT_ARCHIVED), eq(FileSecurityManager.FileOperation.WRITE)))
                .thenReturn(file);

        String result = fileOp.fileWrite(file.toString(), "hello world", FILE_ROOT, NOT_ARCHIVED);

        assertTrue(result.contains("文件已创建"));
        assertTrue(Files.exists(file));
        assertEquals("hello world", Files.readString(file));
    }

    @Test
    void fileWrite_shouldCreateParentDirectories() throws Exception {
        Path file = tempDir.resolve("nested/dir/hh.txt");
        when(securityManager.validate(eq(file.toString()), eq(FILE_ROOT), eq(NOT_ARCHIVED), eq(FileSecurityManager.FileOperation.WRITE)))
                .thenReturn(file);

        fileOp.fileWrite(file.toString(), "nested content", FILE_ROOT, NOT_ARCHIVED);

        assertTrue(Files.exists(file));
        assertEquals("nested content", Files.readString(file));
    }

    @Test
    void fileWrite_shouldOverwriteExistingFile() throws Exception {
        Path file = tempDir.resolve("overwrite.txt");
        Files.writeString(file, "old content");
        when(securityManager.validate(eq(file.toString()), eq(FILE_ROOT), eq(NOT_ARCHIVED), eq(FileSecurityManager.FileOperation.WRITE)))
                .thenReturn(file);

        fileOp.fileWrite(file.toString(), "new content", FILE_ROOT, NOT_ARCHIVED);

        assertEquals("new content", Files.readString(file));
    }

    @Test
    void fileWrite_shouldThrowWhenSecurityFails() {
        String path = "/etc/hosts";
        when(securityManager.validate(eq(path), eq(FILE_ROOT), eq(NOT_ARCHIVED), eq(FileSecurityManager.FileOperation.WRITE)))
                .thenThrow(new DomainException("禁止跨项目访问"));

        assertThrows(DomainException.class, () ->
                fileOp.fileWrite(path, "evil", FILE_ROOT, NOT_ARCHIVED));
    }
}
