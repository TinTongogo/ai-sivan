package com.icusu.sivan.agent.tool;

import com.icusu.sivan.core.tool.ToolResult;
import com.icusu.sivan.core.tool.ToolSpec;
import com.icusu.sivan.infra.file.FileOperationService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 将文件操作工具（file_read/write/list/search）注册到 {@link ToolRegistryImpl}，
 * 供 LLM 直接读写文件，避免绕道 bash heredoc 造成 token 浪费。
 * <p>
 * 工作目录由上游 ReActExecutionStrategy 自动注入 _fileRootPath 和 _archived。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FileToolsRegistrar {

    private final FileOperationService fileOperationService;
    private final ToolRegistryImpl toolRegistry;

    @PostConstruct
    public void init() {
        registerFileRead();
        registerFileWrite();
        registerFileList();
        registerFileSearch();
        log.info("文件工具注册完成（file_read/write/list/search）");
    }

    private void registerFileRead() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("rawPath", Map.of("type", "string", "description", "文件路径（相对项目根目录）"));
        props.put("offset", Map.of("type", "integer", "description", "读取起始行，从 0 开始，默认 0"));
        props.put("limit", Map.of("type", "integer", "description", "最多读取行数，默认全部"));
        schema.put("properties", props);
        schema.put("required", List.of("rawPath"));

        toolRegistry.register(
                new ToolSpec("file_read", "读取文件内容，支持行范围。PDF/DOCX/XLSX 等文档自动提取文本，大文件也能处理。禁止用 bash/python 读取文档内容。替代 bash cat 命令。",
                        schema),
                (call, ctx) -> Mono.fromCallable(() -> {
                    Map<String, Object> args = call.args();
                    String rawPath = (String) args.get("rawPath");
                    if (rawPath == null || rawPath.isBlank()) {
                        return ToolResult.failure("file_read", "rawPath 参数缺失");
                    }
                    String fileRootPath = (String) args.get("_fileRootPath");
                    boolean archived = Boolean.TRUE.equals(args.get("_archived"));
                    int offset = args.getOrDefault("offset", 0) instanceof Number n ? n.intValue() : 0;
                    int limit = args.getOrDefault("limit", 0) instanceof Number n ? n.intValue() : 0;
                    try {
                        String output = fileOperationService.fileRead(rawPath, fileRootPath, archived, offset, limit);
                        return ToolResult.success("file_read", output);
                    } catch (Exception e) {
                        log.warn("file_read 异常: {}", e.getMessage());
                        return ToolResult.failure("file_read", e.getMessage());
                    }
                }));
    }

    private void registerFileWrite() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("rawPath", Map.of("type", "string", "description", "文件路径（相对项目根目录），自动创建父目录"));
        props.put("content", Map.of("type", "string", "description", "文件完整内容"));
        schema.put("properties", props);
        schema.put("required", List.of("rawPath", "content"));

        toolRegistry.register(
                new ToolSpec("file_write", "创建或覆写文件，自动创建父目录。替代 bash heredoc/cat/echo 写入。",
                        schema),
                (call, ctx) -> Mono.fromCallable(() -> {
                    Map<String, Object> args = call.args();
                    String rawPath = (String) args.get("rawPath");
                    String content = (String) args.get("content");
                    if (rawPath == null || rawPath.isBlank()) {
                        return ToolResult.failure("file_write", "rawPath 参数缺失");
                    }
                    String fileRootPath = (String) args.get("_fileRootPath");
                    boolean archived = Boolean.TRUE.equals(args.get("_archived"));
                    try {
                        String output = fileOperationService.fileWrite(rawPath, content, fileRootPath, archived);
                        return ToolResult.success("file_write", output);
                    } catch (Exception e) {
                        log.warn("file_write 异常: {}", e.getMessage());
                        return ToolResult.failure("file_write", e.getMessage());
                    }
                }));
    }

    private void registerFileList() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("rawPath", Map.of("type", "string", "description", "目录路径（相对项目根目录）"));
        props.put("pattern", Map.of("type", "string", "description", "Glob 过滤模式，如 *.java"));
        schema.put("properties", props);
        schema.put("required", List.of("rawPath"));

        toolRegistry.register(
                new ToolSpec("file_list", "列出目录内容。替代 bash ls 命令。",
                        schema),
                (call, ctx) -> Mono.fromCallable(() -> {
                    Map<String, Object> args = call.args();
                    String rawPath = (String) args.get("rawPath");
                    if (rawPath == null || rawPath.isBlank()) {
                        return ToolResult.failure("file_list", "rawPath 参数缺失");
                    }
                    String fileRootPath = (String) args.get("_fileRootPath");
                    boolean archived = Boolean.TRUE.equals(args.get("_archived"));
                    String pattern = (String) args.get("pattern");
                    try {
                        List<Map<String, Object>> entries = fileOperationService.fileList(rawPath, fileRootPath, archived, pattern);
                        StringBuilder sb = new StringBuilder();
                        for (Map<String, Object> e : entries) {
                            sb.append(e.get("directory") == Boolean.TRUE ? "[DIR] " : "[FILE] ");
                            sb.append(e.get("name"));
                            if (e.get("size") instanceof Number size && size.longValue() > 0) {
                                sb.append(" (").append(size).append(" B)");
                            }
                            sb.append("\n");
                        }
                        return ToolResult.success("file_list", sb.toString());
                    } catch (Exception e) {
                        log.warn("file_list 异常: {}", e.getMessage());
                        return ToolResult.failure("file_list", e.getMessage());
                    }
                }));
    }

    private void registerFileSearch() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("rawPath", Map.of("type", "string", "description", "搜索根目录（相对项目根目录）"));
        props.put("searchPattern", Map.of("type", "string", "description", "正则表达式，如 LogFactory"));
        props.put("glob", Map.of("type", "string", "description", "文件 Glob 过滤，如 *.java"));
        props.put("context", Map.of("type", "integer", "description", "匹配行上下文行数，默认 0"));
        schema.put("properties", props);
        schema.put("required", List.of("rawPath", "searchPattern"));

        toolRegistry.register(
                new ToolSpec("file_search", "在目录中搜索文件内容（基于正则）。替代 bash grep 命令。",
                        schema),
                (call, ctx) -> Mono.fromCallable(() -> {
                    Map<String, Object> args = call.args();
                    String rawPath = (String) args.get("rawPath");
                    String searchPattern = (String) args.get("searchPattern");
                    if (rawPath == null || rawPath.isBlank()) {
                        return ToolResult.failure("file_search", "rawPath 参数缺失");
                    }
                    if (searchPattern == null || searchPattern.isBlank()) {
                        return ToolResult.failure("file_search", "searchPattern 参数缺失");
                    }
                    String fileRootPath = (String) args.get("_fileRootPath");
                    boolean archived = Boolean.TRUE.equals(args.get("_archived"));
                    String glob = (String) args.get("glob");
                    int context = args.getOrDefault("context", 0) instanceof Number n ? n.intValue() : 0;
                    try {
                        List<Map<String, Object>> results = fileOperationService.fileSearch(rawPath, fileRootPath, archived, searchPattern, glob, context);
                        StringBuilder sb = new StringBuilder();
                        sb.append("找到 ").append(results.size()).append(" 个匹配结果：\n");
                        for (Map<String, Object> r : results) {
                            sb.append(r.get("file")).append(":").append(r.get("line")).append(" ");
                            sb.append(r.get("content")).append("\n");
                        }
                        return ToolResult.success("file_search", sb.toString());
                    } catch (Exception e) {
                        log.warn("file_search 异常: {}", e.getMessage());
                        return ToolResult.failure("file_search", e.getMessage());
                    }
                }));
    }
}
