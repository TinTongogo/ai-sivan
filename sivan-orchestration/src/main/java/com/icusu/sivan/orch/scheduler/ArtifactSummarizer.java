package com.icusu.sivan.orch.scheduler;

import com.icusu.sivan.orch.executor.OrchestrationEvent;
import com.icusu.sivan.agent.model.ModelRouter;
import com.icusu.sivan.core.message.Msg;
import com.icusu.sivan.core.message.Role;
import com.icusu.sivan.core.model.Model;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 产物摘要生成器 — Task 完成后调用 LLM 对 output/ 新文件生成一行摘要。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ArtifactSummarizer {

    private final ModelRouter modelRouter;

    private static final String SUMMARIZE_PROMPT = """
            为以下文件生成一行简洁的中文摘要（不超过20字），描述其内容或用途。
            只输出摘要文本，不要额外说明。

            文件路径：{filePath}
            文件内容（前500字）：
            {content}
            """;

    /**
     * 对产物列表生成摘要，返回 filePath → summary 的映射。
     */
    public Map<String, String> summarize(UUID accountId, List<OrchestrationEvent.ArtifactInfo> artifacts, String rootPath) {
        Map<String, String> result = new HashMap<>();
        if (artifacts == null || artifacts.isEmpty()) return result;

        for (OrchestrationEvent.ArtifactInfo artifact : artifacts) {
            try {
                Path filePath = Paths.get(rootPath, artifact.filePath());
                String content = readFirstChars(filePath, 500);
                if (content.isBlank()) {
                    result.put(artifact.filePath(), fileNameFromPath(artifact.filePath()));
                    continue;
                }

                String prompt = SUMMARIZE_PROMPT
                        .replace("{filePath}", artifact.filePath())
                        .replace("{content}", content);

                Model.ModelResponse response = Mono.fromCallable(() ->
                                modelRouter.getDefaultModel(accountId)
                                        .chat(List.of(
                                                Msg.of(Role.SYSTEM, summarizeSystem()),
                                                Msg.of(Role.USER, prompt)
                                        ), Model.ModelParams.defaults())
                                        .block(Duration.ofSeconds(15)))
                        .subscribeOn(Schedulers.boundedElastic())
                        .block();

                String summary = response != null && response.msg() != null
                        ? response.msg().text().strip()
                        : fileNameFromPath(artifact.filePath());
                if (summary.length() > 50) summary = summary.substring(0, 50);
                result.put(artifact.filePath(), summary);
            } catch (Exception e) {
                log.warn("产物摘要生成失败: file={}, error={}", artifact.filePath(), e.getMessage());
                result.put(artifact.filePath(), fileNameFromPath(artifact.filePath()));
            }
        }
        return result;
    }

    private static String readFirstChars(Path path, int maxChars) {
        if (path == null || !Files.exists(path) || !Files.isRegularFile(path)) return "";
        try {
            byte[] bytes = Files.readAllBytes(path);
            String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            return text.length() > maxChars ? text.substring(0, maxChars) : text;
        } catch (IOException e) {
            return "";
        }
    }

    private static String summarizeSystem() {
        return "你是一个文件摘要助手。根据文件内容生成一行精简的中文摘要。";
    }

    private static String fileNameFromPath(String filePath) {
        if (filePath == null || filePath.isBlank()) return "";
        int idx = filePath.lastIndexOf('/');
        if (idx < 0) idx = filePath.lastIndexOf('\\');
        return idx >= 0 ? filePath.substring(idx + 1) : filePath;
    }
}
