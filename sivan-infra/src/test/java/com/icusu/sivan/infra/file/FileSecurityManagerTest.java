package com.icusu.sivan.infra.file;

import com.icusu.sivan.common.exception.DomainException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class FileSecurityManagerTest {

    private FileSecurityManager securityManager;

    @TempDir
    Path tempDir;

    private Path rootPath;
    private Path projectDir;

    @BeforeEach
    void setUp() throws Exception {
        securityManager = new FileSecurityManager();
        rootPath = tempDir.resolve("sivan_data");
        Files.createDirectories(rootPath);
        org.springframework.test.util.ReflectionTestUtils.setField(securityManager, "rootPath", rootPath.toString());

        // 项目目录: {root}/{acctShortId}/{projShortId}
        projectDir = rootPath.resolve("acct-1").resolve("proj-a");
        Files.createDirectories(projectDir);

        // 共享目录
        Files.createDirectories(rootPath.resolve("shared"));

        // 其他项目目录
        Files.createDirectories(rootPath.resolve("acct-1").resolve("other-proj"));
    }

    private String fileRootPath() {
        return projectDir.toString();
    }

    @Test
    void validate_shouldAllowReadInProjectDir() {
        String path = projectDir.resolve("test.txt").toString();
        Path result = securityManager.validate(path, fileRootPath(), false, FileSecurityManager.FileOperation.READ);
        assertNotNull(result);
    }

    @Test
    void validate_shouldAllowReadInSharedDir() {
        String path = rootPath.resolve("shared/common.txt").toString();
        Path result = securityManager.validate(path, fileRootPath(), false, FileSecurityManager.FileOperation.READ);
        assertNotNull(result);
    }

    @Test
    void validate_shouldBlockWriteInSharedDir() {
        String path = rootPath.resolve("shared/common.txt").toString();
        assertThrows(DomainException.class, () ->
                securityManager.validate(path, fileRootPath(), false, FileSecurityManager.FileOperation.WRITE));
    }

    @Test
    void validate_shouldBlockCrossProjectAccess() {
        String path = rootPath.resolve("acct-1").resolve("other-proj").resolve("secret.txt").toString();
        assertThrows(DomainException.class, () ->
                securityManager.validate(path, fileRootPath(), false, FileSecurityManager.FileOperation.READ));
    }

    @Test
    void validate_shouldBlockPathOutsideRoot() {
        assertThrows(DomainException.class, () ->
                securityManager.validate("/etc/passwd", fileRootPath(), false, FileSecurityManager.FileOperation.READ));
    }

    @Test
    void validate_shouldBlockWriteOnArchivedProject() {
        String path = projectDir.resolve("file.txt").toString();
        assertThrows(DomainException.class, () ->
                securityManager.validate(path, fileRootPath(), true, FileSecurityManager.FileOperation.WRITE));
    }

    @Test
    void validate_shouldAllowReadOnArchivedProject() {
        String path = projectDir.resolve("file.txt").toString();
        Path result = securityManager.validate(path, fileRootPath(), true, FileSecurityManager.FileOperation.READ);
        assertNotNull(result);
    }

    @Test
    void validate_shouldBlockSymlinkEscape() throws Exception {
        // 在项目内创建指向外部文件的符号链接
        Path outsideFile = rootPath.resolve("outside.txt");
        Files.writeString(outsideFile, "outside data");
        Path symlink = projectDir.resolve("evil_link");
        Files.createSymbolicLink(symlink, outsideFile);

        // 通过符号链接读取应被拦截（toRealPath 解析后路径不在项目内）
        assertThrows(DomainException.class, () ->
                securityManager.validate(symlink.toString(), fileRootPath(), false,
                        FileSecurityManager.FileOperation.READ));
    }

    @Test
    void validate_shouldBlockSymlinkChainEscape() throws Exception {
        // 两级符号链接：项目内 link1 -> link2 -> /etc/passwd
        Path outsideFile = rootPath.resolve("target.txt");
        Files.writeString(outsideFile, "target");
        Path link2 = rootPath.resolve("acct-1").resolve("link2");
        Files.createSymbolicLink(link2, outsideFile);
        Path link1 = projectDir.resolve("link1");
        Files.createSymbolicLink(link1, link2);

        assertThrows(DomainException.class, () ->
                securityManager.validate(link1.toString(), fileRootPath(), false,
                        FileSecurityManager.FileOperation.READ));
    }

    @Test
    void validate_shouldAllowNewFileInProject() {
        // 新文件不存在，但父目录存在 → 应允许写入
        String path = projectDir.resolve("output").resolve("new_file.txt").toString();
        Path result = securityManager.validate(path, fileRootPath(), false, FileSecurityManager.FileOperation.WRITE);
        assertNotNull(result);
    }

    @Test
    void validate_shouldResolveSymlinksInProjectDir() throws Exception {
        // 项目内符号链接指向项目内另一文件 → 应该允许
        Path target = projectDir.resolve("allowed.txt");
        Files.writeString(target, "allowed");
        Path symlink = projectDir.resolve("internal_link");
        Files.createSymbolicLink(symlink, target);

        Path result = securityManager.validate(symlink.toString(), fileRootPath(), false,
                FileSecurityManager.FileOperation.READ);
        assertNotNull(result);
        // 解析后应指向真实文件（不在项目内需拦截）
        assertTrue(result.toString().contains("allowed.txt"));
    }
}
