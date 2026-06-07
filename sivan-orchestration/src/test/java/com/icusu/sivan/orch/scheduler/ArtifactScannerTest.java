package com.icusu.sivan.orch.scheduler;

import com.icusu.sivan.orch.executor.OrchestrationEvent;
import com.icusu.sivan.domain.goal.Goal;
import com.icusu.sivan.domain.goal.IGoalArtifactRepository;
import com.icusu.sivan.domain.goal.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ArtifactScannerTest {

    @Mock private IGoalArtifactRepository artifactRepository;
    private ArtifactScanner scanner;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        scanner = new ArtifactScanner(artifactRepository);
    }

    @Test
    void scan_rootPath为空返回空() {
        Goal goal = Goal.builder().goalId(UUID.randomUUID()).build();
        Task task = Task.builder().order(0).build();
        assertTrue(scanner.scan(goal, task).isEmpty());
        verifyNoInteractions(artifactRepository);
    }

    @Test
    void scan_output目录不存在返回空() {
        Goal goal = Goal.builder().goalId(UUID.randomUUID()).fileRootPath("/nonexistent").build();
        Task task = Task.builder().order(0).build();
        assertTrue(scanner.scan(goal, task).isEmpty());
    }

    @Test
    void scan_检测到新产物() throws Exception {
        Path outputDir = Paths.get(tempDir.toString(), "output");
        Files.createDirectories(outputDir);
        Path outputFile = Files.createTempFile(outputDir, "result", ".txt");
        Files.writeString(outputFile, "test content");
        Thread.sleep(100); // 确保文件时间戳在 60s 窗口内

        doNothing().when(artifactRepository).saveAll(anyList());

        Goal goal = Goal.builder().goalId(UUID.randomUUID()).fileRootPath(tempDir.toString()).build();
        Task task = Task.builder().order(1).build();

        List<OrchestrationEvent.ArtifactInfo> artifacts = scanner.scan(goal, task);
        assertFalse(artifacts.isEmpty());
        verify(artifactRepository).saveAll(anyList());
    }

    @Test
    void scan_旧文件不扫描() throws IOException {
        Path outputDir = Paths.get(tempDir.toString(), "output");
        Files.createDirectories(outputDir);
        Path oldFile = Files.createTempFile(outputDir, "old", ".log");
        Files.writeString(oldFile, "old content");
        // 修改文件时间为 2 分钟前
        Files.setLastModifiedTime(oldFile, java.nio.file.attribute.FileTime.fromMillis(
                System.currentTimeMillis() - 120_000));

        Goal goal = Goal.builder().goalId(UUID.randomUUID()).fileRootPath(tempDir.toString()).build();
        Task task = Task.builder().order(0).build();

        assertTrue(scanner.scan(goal, task).isEmpty());
    }

    @Test
    void scan_output目录有文件但不在窗口内() throws IOException {
        Path outputDir = Paths.get(tempDir.toString(), "output");
        Files.createDirectories(outputDir);

        Goal goal = Goal.builder().goalId(UUID.randomUUID()).fileRootPath(tempDir.toString()).build();
        Task task = Task.builder().order(0).build();

        assertEquals(0, scanner.scan(goal, task).size());
    }

    // ── inferFileType 通过 scan 间接测试 ──

    @Test
    void inferFileType_代码文件识别() throws Exception {
        Path outputDir = Paths.get(tempDir.toString(), "output");
        Files.createDirectories(outputDir);
        Files.writeString(Files.createTempFile(outputDir, "Main", ".java"), "code");
        Thread.sleep(100);

        doNothing().when(artifactRepository).saveAll(anyList());

        Goal goal = Goal.builder().goalId(UUID.randomUUID()).fileRootPath(tempDir.toString()).build();
        Task task = Task.builder().order(0).build();

        List<OrchestrationEvent.ArtifactInfo> artifacts = scanner.scan(goal, task);
        assertFalse(artifacts.isEmpty());
        assertTrue(artifacts.get(0).fileType().equals("code") || artifacts.get(0).fileType().equals("data"));
    }
}
