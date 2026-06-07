package com.icusu.sivan.agent.tool;

import com.icusu.sivan.core.tool.ToolResult;
import com.icusu.sivan.core.tool.ToolSpec;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 将 bash 执行工具注册到 {@link ToolRegistryImpl}，供 LLM 执行代码和 shell 命令。
 * <p>
 * 工作目录由上游 ReActExecutionStrategy 自动注入 _fileRootPath，LLM 无需感知。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BashToolsRegistrar {

    private final BashService bashService;
    private final ToolRegistryImpl toolRegistry;

    @PostConstruct
    public void init() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("command", Map.of("type", "string", "description", "要执行的 shell 命令，如 python3 script.py"));
        props.put("timeout", Map.of("type", "integer", "description", "超时秒数，默认 30"));
        schema.put("properties", props);
        schema.put("required", List.of("command"));

        toolRegistry.register(
                new ToolSpec("bash", "在项目目录中执行 shell 命令和脚本。运行 Python/shell 脚本用此工具。查看文件内容请用 file_read（支持 PDF/DOCX 文本提取），目录列表用 file_list，搜索用 file_search。工作目录已锁定为项目根目录。",
                        schema),
                (call, ctx) -> Mono.fromCallable(() -> {
                    Map<String, Object> args = call.args();
                    String command = (String) args.get("command");
                    if (command == null || command.isBlank()) {
                        return ToolResult.failure("bash", "command 参数缺失");
                    }
                    String fileRootPath = (String) args.get("_fileRootPath");
                    boolean archived = Boolean.TRUE.equals(args.get("_archived"));
                    try {
                        String output = bashService.execute(command, fileRootPath, archived);
                        return ToolResult.success("bash", output);
                    } catch (Exception e) {
                        log.error("bash 执行异常: {}", e.getMessage());
                        return ToolResult.failure("bash", e.getMessage());
                    }
                }));
        log.info("bash 工具注册完成");
    }
}
