package com.icusu.sivan.orch.scheduler;

import com.icusu.sivan.orch.executor.OrchestrationEvent;
import com.icusu.sivan.domain.goal.Goal;
import com.icusu.sivan.domain.goal.GoalArtifact;
import com.icusu.sivan.domain.goal.IGoalArtifactRepository;
import com.icusu.sivan.domain.goal.Task;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 产物扫描器 — Task 完成后扫描 output/ 目录记录新文件。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ArtifactScanner {

    private final IGoalArtifactRepository artifactRepository;

    /**
     * 扫描 Task 执行产生的产物文件。
     * 返回本次扫描到的产物列表（已持久化到 DB）。
     */
    public List<OrchestrationEvent.ArtifactInfo> scan(Goal goal, Task task) {
        List<OrchestrationEvent.ArtifactInfo> result = new ArrayList<>();

        String rootPath = goal.getFileRootPath();
        if (rootPath == null || rootPath.isBlank()) {
            return result;
        }

        Path outputDir = Paths.get(rootPath, "output");
        if (!Files.exists(outputDir) || !Files.isDirectory(outputDir)) {
            return result;
        }

        long now = System.currentTimeMillis();
        try (Stream<Path> walk = Files.walk(outputDir)) {
            List<GoalArtifact> artifacts = walk
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        try {
                            // 只记录 30 秒内修改过的文件（当前 Task 产生的）
                            return Files.getLastModifiedTime(p).toMillis() > now - 60_000;
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .map(p -> {
                        String relativePath = outputDir.getParent().relativize(p).toString();
                        String fileName = p.getFileName().toString();
                        String fileType = inferFileType(fileName);
                        long fileSize;
                        try { fileSize = Files.size(p); } catch (IOException e) { fileSize = 0; }

                        return GoalArtifact.builder()
                                .goalId(goal.getGoalId())
                                .milestoneOrder(goal.getCurrentMilestone())
                                .taskOrder(task.getOrder())
                                .filePath(relativePath)
                                .fileType(fileType)
                                .summary(fileName)
                                .fileSize(fileSize)
                                .build();
                    })
                    .toList();

            if (!artifacts.isEmpty()) {
                artifactRepository.saveAll(artifacts);
                for (GoalArtifact a : artifacts) {
                    result.add(new OrchestrationEvent.ArtifactInfo(
                            a.getFilePath(), a.getFileType(), a.getSummary(), a.getFileSize()));
                }
            }
        } catch (IOException e) {
            log.warn("产物扫描失败: {}", e.getMessage());
        }

        return result;
    }

    /** 根据文件后缀推断类型。 */
    private static String inferFileType(String fileName) {
        if (fileName == null) return "data";
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".java") || lower.endsWith(".kt") || lower.endsWith(".py")
                || lower.endsWith(".js") || lower.endsWith(".ts") || lower.endsWith(".go")
                || lower.endsWith(".rs") || lower.endsWith(".xml") || lower.endsWith(".yml")
                || lower.endsWith(".yaml") || lower.endsWith(".json") || lower.endsWith(".sql")
                || lower.endsWith(".sh") || lower.endsWith(".css") || lower.endsWith(".html"))
            return "code";
        if (lower.endsWith(".md") || lower.endsWith(".txt") || lower.endsWith(".rst")
                || lower.endsWith(".pdf") || lower.endsWith(".doc") || lower.endsWith(".docx"))
            return "doc";
        if (lower.endsWith(".csv") || lower.endsWith(".tsv") || lower.endsWith(".xls")
                || lower.endsWith(".xlsx") || lower.endsWith(".json") || lower.endsWith(".xml"))
            return "data";
        if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".gif") || lower.endsWith(".svg") || lower.endsWith(".webp"))
            return "image";
        return "data";
    }
}
