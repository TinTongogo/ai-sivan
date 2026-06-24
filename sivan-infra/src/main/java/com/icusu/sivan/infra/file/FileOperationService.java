package com.icusu.sivan.infra.file;

import com.icusu.sivan.common.exception.DomainException;
import com.icusu.sivan.infra.file.FileSecurityManager.FileOperation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

/**
 * 文件操作服务，所有文件操作均经过 FileSecurityManager 校验。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileOperationService {

    private final FileSecurityManager securityManager;
    private final DocumentTextExtractor documentTextExtractor;

    @Value("${sivan.file.max-file-size:1048576}")
    private long maxFileSize;

    private static final Set<String> BINARY_DOC_EXTENSIONS = Set.of(
            ".pdf", ".docx", ".doc", ".xlsx", ".xls", ".pptx", ".ppt",
            ".odt", ".ods", ".odp"
    );

    /** 文本提取最大字符数（~500KB 纯文本，用于 file_read 兜底）。 */
    private static final int FILE_READ_MAX_CHARS = 500_000;

    /** 未指定 limit 时默认预览行数，超出则提示 LLM 使用 offset/limit 分段读取。 */
    private static final int PREVIEW_MAX_LINES = 100;

    /** 二进制文档（PDF/DOCX 等）原始大小上限（50MB），Tika 会提取文本后返回。 */
    private static final long BINARY_MAX_FILE_SIZE = 50 * 1024 * 1024;

    /**
     * 读取文件内容，支持行范围。
     * <p>策略（让 LLM 先看概览再分段读取）：
     * <ul>
     *   <li>未指定 offset/limit → 返回文件总行数 + 前 {@link #PREVIEW_MAX_LINES} 行预览</li>
     *   <li>指定了 offset/limit → 返回指定行段 + 文件总行数</li>
     * </ul>
     * 二进制文档（PDF、DOCX 等）自动走 Tika 文本提取。
     */
    public String fileRead(String rawPath, String fileRootPath, boolean archived, int offset, int limit) {
        Path path = securityManager.validate(rawPath, fileRootPath, archived, FileOperation.READ);
        if (!Files.exists(path)) throw new DomainException("文件不存在: " + rawPath);
        if (!Files.isRegularFile(path)) throw new DomainException("不是一个文件: " + rawPath);

        // 二进制文档走 Tika 文本提取（忽略行范围，整文件提取）
        if (isBinaryDocumentFile(path)) {
            return extractBinaryDocument(rawPath, path);
        }

        try {
            if (Files.size(path) > maxFileSize) {
                return "[文件 " + rawPath + " 过大 (" + Files.size(path) + " 字节)，超过 file_read 限制。\n"
                        + "可用 bash sed/head/tail 分段读取，或用 file_search 聚焦查找目标内容]";
            }
        } catch (IOException e) {
            throw new DomainException("读取文件大小失败: " + e.getMessage());
        }
        try {
            List<String> allLines = Files.readAllLines(path);
            int totalLines = allLines.size();

            // 未指定 limit：智能预览模式 — 返回文件总行数 + 前 N 行预览
            if (limit <= 0) {
                int previewEnd = Math.min(totalLines, PREVIEW_MAX_LINES);
                StringBuilder sb = new StringBuilder();
                sb.append("[文件信息] ").append(rawPath)
                        .append(" 共 ").append(totalLines).append(" 行")
                        .append("，").append(Files.size(path)).append(" 字节\n");
                if (totalLines > PREVIEW_MAX_LINES) {
                    sb.append("[预览行 1-").append(previewEnd).append(" / ").append(totalLines)
                            .append("，可通过 offset/limit 参数分段读取更多]\n");
                }
                sb.append("━━━━━━━━━━━━━━━━━━━━\n");
                for (int i = 0; i < previewEnd; i++) {
                    sb.append(allLines.get(i)).append('\n');
                }
                if (totalLines > previewEnd) {
                    sb.append("━━━━━━━━━━━━━━━━━━━━\n");
                    sb.append("[剩余 ").append(totalLines - previewEnd).append(" 行未显示，指定 offset=")
                            .append(previewEnd).append(" limit=").append(PREVIEW_MAX_LINES)
                            .append(" 读取下一段]\n");
                }
                return sb.toString();
            }

            // 指定了 offset/limit：返回指定行段 + 文件总行数
            int start = Math.max(0, offset);
            int end = Math.min(totalLines, start + limit);
            StringBuilder sb = new StringBuilder();
            sb.append("[文件信息] ").append(rawPath)
                    .append(" 共 ").append(totalLines).append(" 行")
                    .append("，显示行 ").append(start + 1).append("-").append(end)
                    .append(" / ").append(totalLines).append("\n");
            sb.append("━━━━━━━━━━━━━━━━━━━━\n");
            for (int i = start; i < end; i++) {
                sb.append(allLines.get(i)).append('\n');
            }
            return sb.toString();
        } catch (IOException e) {
            throw new DomainException("读取文件失败: " + e.getMessage());
        }
    }

    /** 二进制文档走 Tika 提取。 */
    private String extractBinaryDocument(String rawPath, Path path) {
        String mimeType = detectMimeTypeByExtension(path);
        try {
            byte[] bytes = Files.readAllBytes(path);
            if (bytes.length > BINARY_MAX_FILE_SIZE) {
                throw new DomainException("文件过大，超过 " + BINARY_MAX_FILE_SIZE / (1024 * 1024) + "MB 限制");
            }
            String extracted = documentTextExtractor.extractText(bytes, mimeType, FILE_READ_MAX_CHARS);
            if (extracted == null) {
                return "[文档解析失败: " + rawPath + "，无法提取文本内容]";
            }
            return "[文档文本提取: " + rawPath + "]\n" + extracted;
        } catch (IOException e) {
            log.warn("读取二进制文档失败: {}", rawPath, e);
            return "[文档读取失败: " + e.getMessage() + "]";
        }
    }

    /**
     * 列出目录内容，支持 glob 模式过滤。
     */
    public List<Map<String, Object>> fileList(String rawPath, String fileRootPath, boolean archived, String pattern) {
        Path path = securityManager.validate(rawPath, fileRootPath, archived, FileOperation.READ);
        if (!Files.exists(path)) throw new DomainException("路径不存在: " + rawPath);
        if (!Files.isDirectory(path)) throw new DomainException("不是一个目录: " + rawPath);

        final PathMatcher matcher = (pattern != null && !pattern.isBlank())
                ? path.getFileSystem().getPathMatcher("glob:" + pattern) : null;

        List<Map<String, Object>> entries = new ArrayList<>();
        try (Stream<Path> stream = Files.list(path)) {
            stream.sorted((a, b) -> {
                boolean aDir = Files.isDirectory(a);
                boolean bDir = Files.isDirectory(b);
                if (aDir != bDir) return aDir ? -1 : 1;
                return a.getFileName().toString().compareToIgnoreCase(b.getFileName().toString());
            }).forEach(p -> {
                if (matcher != null && !matcher.matches(p.getFileName())) return;
                try {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("name", p.getFileName().toString());
                    entry.put("directory", Files.isDirectory(p));
                    entry.put("size", Files.isRegularFile(p) ? Files.size(p) : 0);
                    entry.put("lastModified", Files.getLastModifiedTime(p).toMillis());
                    entries.add(entry);
                } catch (IOException ignored) { /* skip */ }
            });
        } catch (IOException e) {
            throw new DomainException("列出目录失败: " + e.getMessage());
        }
        return entries;
    }

    /** 禁止写入的可执行文件扩展名（小写）。 */
    private static final Set<String> BLOCKED_WRITE_EXTENSIONS = Set.of(
            "exe", "bin", "so", "dylib", "dll", "o", "class", "jar",
            "wasm", "out", "app", "msi", "deb", "rpm"
    );

    public String fileWrite(String rawPath, String content, String fileRootPath, boolean archived, boolean append) {
        Path path = securityManager.validate(rawPath, fileRootPath, archived, FileOperation.WRITE);
        if (Files.isDirectory(path)) throw new DomainException("路径是一个目录，无法作为文件写入: " + rawPath);

        // 禁止写入可执行文件类型
        String fileName = path.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        if (dot > 0 && dot < fileName.length() - 1) {
            String ext = fileName.substring(dot + 1).toLowerCase();
            if (BLOCKED_WRITE_EXTENSIONS.contains(ext)) {
                throw new DomainException("禁止写入可执行文件类型: ." + ext);
            }
        }

        try {
            Files.createDirectories(path.getParent());
            String safeContent = content != null ? content : "";
            if (append && Files.exists(path)) {
                Files.writeString(path, safeContent, java.nio.file.StandardOpenOption.APPEND);
                log.info("文件追加成功: {}", rawPath);
                return "文件已追加: " + rawPath + " (" + Files.size(path) + " 字节)";
            } else {
                Files.writeString(path, safeContent);
                log.info("文件写入成功: {}", rawPath);
                return "文件已创建/写入: " + rawPath + " (" + Files.size(path) + " 字节)";
            }
        } catch (IOException e) {
            throw new DomainException("写入文件失败: " + e.getMessage());
        }
    }


    /**
     * 删除文件或空目录。目录非空时抛出异常。
     */
    public String fileDelete(String rawPath, String fileRootPath, boolean archived) {
        Path path = securityManager.validate(rawPath, fileRootPath, archived, FileOperation.DELETE);
        if (!Files.exists(path)) throw new DomainException("路径不存在: " + rawPath);
        try {
            if (Files.isDirectory(path)) {
                try (var files = Files.list(path)) {
                    if (files.findAny().isPresent()) {
                        throw new DomainException("目录非空，无法删除: " + rawPath
                                + "。如需删除非空目录请先清空或使用 bash rm -rf");
                    }
                }
                Files.delete(path);
                log.info("目录删除成功: {}", rawPath);
                return "目录已删除: " + rawPath;
            } else {
                Files.delete(path);
                log.info("文件删除成功: {}", rawPath);
                return "文件已删除: " + rawPath;
            }
        } catch (IOException e) {
            throw new DomainException("删除失败: " + e.getMessage());
        }
    }

    public String fileEdit(String rawPath, String oldText, String newText, String fileRootPath, boolean archived) {
        java.nio.file.Path path = securityManager.validate(rawPath, fileRootPath, archived, FileOperation.WRITE);
        if (!java.nio.file.Files.exists(path)) throw new DomainException("文件不存在: " + rawPath);
        if (java.nio.file.Files.isDirectory(path)) throw new DomainException("路径是一个目录: " + rawPath);
        try {
            String content = java.nio.file.Files.readString(path);
            int idx = content.indexOf(oldText);
            if (idx == -1) {
                String preview = oldText.length() > 80 ? oldText.substring(0, 77) + "..." : oldText;
                throw new DomainException("在文件中未找到匹配的文本: \"" + preview + "\"。请确保 oldText 与文件中的内容完全一致");
            }
            String newContent = content.substring(0, idx) + newText + content.substring(idx + oldText.length());
            java.nio.file.Files.writeString(path, newContent);
            log.info("文件编辑成功: {}", rawPath);
            return "文件已编辑: " + rawPath + " (" + oldText.length() + " 字符已替换)";
        } catch (java.io.IOException e) {
            throw new DomainException("编辑文件失败: " + e.getMessage());
        }
    }

    /**
     * 内容搜索，基于正则表达式。
     */
    public List<Map<String, Object>> fileSearch(String rawPath, String fileRootPath, boolean archived,
                                                 String searchPattern, String glob, int context) {
        Path path = securityManager.validate(rawPath, fileRootPath, archived, FileOperation.READ);
        if (!Files.exists(path)) throw new DomainException("路径不存在: " + rawPath);
        if (!Files.isDirectory(path)) throw new DomainException("搜索根路径不是目录: " + rawPath);

        Pattern regex;
        try {
            regex = Pattern.compile(searchPattern, Pattern.CASE_INSENSITIVE);
        } catch (PatternSyntaxException e) {
            throw new DomainException("无效的正则表达式: " + e.getMessage());
        }

        final PathMatcher fileMatcher = (glob != null && !glob.isBlank())
                ? path.getFileSystem().getPathMatcher("glob:" + glob) : null;

        final List<Map<String, Object>> results = new ArrayList<>();
        final int maxResults = 500;
        try (Stream<Path> stream = Files.walk(path, 10)) {
            stream.filter(Files::isRegularFile).forEach(file -> {
                if (results.size() >= maxResults) return;
                if (fileMatcher != null && !fileMatcher.matches(file.getFileName())) return;
                try {
                    List<String> lines = Files.readAllLines(file);
                    for (int i = 0; i < lines.size(); i++) {
                        if (results.size() >= maxResults) break;
                        var matcher = regex.matcher(lines.get(i));
                        if (matcher.find()) {
                            Map<String, Object> result = new LinkedHashMap<>();
                            result.put("file", path.relativize(file).toString());
                            result.put("line", i + 1);
                            result.put("content", lines.get(i).trim());
                            if (context > 0) {
                                int ctxStart = Math.max(0, i - context);
                                int ctxEnd = Math.min(lines.size(), i + context + 1);
                                List<String> ctx = new ArrayList<>();
                                for (int j = ctxStart; j < ctxEnd; j++) {
                                    ctx.add((j == i ? "> " : "  ") + lines.get(j));
                                }
                                result.put("context", ctx);
                            }
                            results.add(result);
                        }
                    }
                } catch (IOException ignored) { /* skip */ }
            });
        } catch (IOException e) {
            throw new DomainException("搜索失败: " + e.getMessage());
        }
        return results;
    }

    // ---- 二进制文档辅助方法 ----

    /**
     * 判断文件是否为二进制文档格式（PDF、Office 等），
     * 先检查扩展名，再检查魔数。
     */
    private boolean isBinaryDocumentFile(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        for (String ext : BINARY_DOC_EXTENSIONS) {
            if (name.endsWith(ext)) return true;
        }
        // 魔数二次检测：覆盖无扩展名或非常见扩展的二进制文档
        try (InputStream is = Files.newInputStream(path)) {
            byte[] header = new byte[4];
            int read = is.read(header);
            if (read >= 4) {
                // PDF: %PDF
                if (header[0] == 0x25 && header[1] == 0x50 && header[2] == 0x44 && header[3] == 0x46) return true;
                // ZIP (docx/xlsx/pptx/odt): PK\x03\x04
                if (header[0] == 0x50 && header[1] == 0x4B && header[2] == 0x03 && header[3] == 0x04) return true;
            }
        } catch (IOException e) {
            log.debug("读取魔数失败，回退文本读取: {}", path, e);
        }
        return false;
    }

    /** 根据扩展名推断 MIME 类型（用于 Tika 日志标记）。 */
    private static String detectMimeTypeByExtension(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        if (name.endsWith(".pdf")) return "application/pdf";
        if (name.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (name.endsWith(".doc")) return "application/msword";
        if (name.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if (name.endsWith(".xls")) return "application/vnd.ms-excel";
        if (name.endsWith(".pptx")) return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
        if (name.endsWith(".ppt")) return "application/vnd.ms-powerpoint";
        if (name.endsWith(".odt")) return "application/vnd.oasis.opendocument.text";
        if (name.endsWith(".ods")) return "application/vnd.oasis.opendocument.spreadsheet";
        if (name.endsWith(".odp")) return "application/vnd.oasis.opendocument.presentation";
        return "application/octet-stream";
    }

}
