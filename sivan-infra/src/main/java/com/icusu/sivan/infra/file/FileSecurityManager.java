package com.icusu.sivan.infra.file;

import com.icusu.sivan.common.exception.DomainException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * 文件安全校验器，确保文件操作限制在项目目录内（07-工具动态感知 §4.7.1）。
 * <p>使用 {@link Path#toRealPath(java.nio.file.LinkOption...)} 解析符号链接，
 * 防止通过项目内符号链接逃逸到外部路径。</p>
 *
 * <h3>安全检查规则</h3>
 * <ul>
 *   <li>路径穿越防护：{@code ../} 解析后必须在项目目录内</li>
 *   <li>符号链接解析：防止 symlink 逃逸</li>
 *   <li>禁止列表：{@code .env}、{@code .ssh}、{@code config/keys} 等禁止读写</li>
 *   <li>写目录限制：{@code file_write} 只允许写入 {@code data/}、{@code output/}、{@code uploads/}</li>
 * </ul>
 */
@Slf4j
@Component
public class FileSecurityManager {

    @Value("${sivan.file.root-path}")
    private String rootPath;

    /** 禁止访问的文件名/目录名（07-工具动态感知 §4.7.1）。 */
    private static final List<String> FORBIDDEN_NAMES = List.of(
            ".env", ".ssh", ".git", ".gitignore",
            "config/keys", "config/secrets", ".secret", ".token"
    );

    /** 写操作允许的子目录（07-工具动态感知 §4.7.1）。 */
    private static final List<String> ALLOWED_WRITE_DIRS = List.of(
            "data", "output", "uploads"
    );

    public enum FileOperation { READ, WRITE, DELETE }

    /**
     * 校验文件操作是否在项目允许范围内。
     * @param rawPath      请求的文件路径
     * @param fileRootPath 项目根目录（{root}/{acctShortId}/{projectShortId}）
     * @param archived     项目是否已归档
     * @param operation    操作类型
     * @return 标准化后的路径（已解析符号链接）
     */
    public Path validate(String rawPath, String fileRootPath, boolean archived, FileOperation operation) {
        Path projectRoot = resolveRealPath(Paths.get(fileRootPath));

        // 路径解析：绝对路径直接使用，相对路径拼到项目根目录
        Path path;
        if (rawPath != null && !rawPath.isBlank()) {
            Path parsed = Paths.get(rawPath);
            if (parsed.isAbsolute()) {
                path = resolveAgainstProject(parsed, projectRoot);
            } else {
                path = resolveAgainstProject(projectRoot.resolve(rawPath), projectRoot);
            }
        } else {
            path = projectRoot;
        }

        // 1. 禁止列表检查
        checkForbidden(path, projectRoot);

        // 2. 写目录限制检查
        if (operation == FileOperation.WRITE) {
            checkAllowedWriteDir(path, projectRoot);
        }

        // 3. 检查是否在项目目录内
        if (path.startsWith(projectRoot)) {
            if (operation != FileOperation.READ && archived) {
                throw new DomainException("项目已归档，文件为只读状态");
            }
            return path;
        }

        // 4. 检查是否在共享目录（只读放行）
        Path sharedRoot = resolveRealPath(Paths.get(rootPath).resolve("shared"));
        if (operation == FileOperation.READ && path.startsWith(sharedRoot)) {
            return path;
        }

        log.warn("跨项目文件访问被拒绝: fileRootPath={} path={} operation={}",
                fileRootPath, rawPath, operation);
        throw new DomainException("禁止跨项目访问文件: " + rawPath);
    }

    /**
     * 检查路径是否包含禁止访问的文件名/目录名。
     */
    private void checkForbidden(Path path, Path projectRoot) {
        try {
            String relative = projectRoot.relativize(path).toString().toLowerCase();
            for (String forbidden : FORBIDDEN_NAMES) {
                if (relative.contains(forbidden.toLowerCase())) {
                    log.warn("禁止访问敏感文件: path={} matched={}", path, forbidden);
                    throw new DomainException("禁止访问敏感文件: " + path.getFileName());
                }
            }
        } catch (IllegalArgumentException e) {
            // relativize 失败说明不在项目内，后续检查会处理
        }
    }

    /**
     * 检查写操作的目标是否在允许的子目录内。
     */
    private void checkAllowedWriteDir(Path path, Path projectRoot) {
        try {
            String relative = projectRoot.relativize(path).normalize().toString();
            boolean allowed = ALLOWED_WRITE_DIRS.stream()
                    .anyMatch(dir -> relative.equals(dir) || relative.startsWith(dir + "/"));
            if (!allowed) {
                log.warn("写操作目标不在允许的目录内: path={} relative={}", path, relative);
                throw new DomainException("文件写入只允许在 data/、output/、uploads/ 目录下进行");
            }
        } catch (IllegalArgumentException e) {
            // 不在项目内，后续检查会处理
        }
    }

    /**
     * 解析为真实路径（跟随符号链接），防止 symlink 逃逸。
     * 如果路径不存在，则逐级解析存在的父目录，防止祖先链中存在符号链接。
     */
    private static Path resolveRealPath(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            // 路径不存在时向上逐级解析存在的父目录
            Path resolved = path.toAbsolutePath().normalize();
            int nameCount = resolved.getNameCount();
            for (int i = nameCount - 1; i >= 0; i--) {
                Path sub = resolved.subpath(0, i + 1);
                Path full = resolved.getRoot().resolve(sub);
                if (Files.exists(full)) {
                    try {
                        Path realParent = full.toRealPath();
                        // 将不存在的子路径拼回
                        if (i < nameCount - 1) {
                            Path rest = resolved.subpath(i + 1, nameCount);
                            return realParent.resolve(rest);
                        }
                        return realParent;
                    } catch (IOException ignored) {
                        // continue backing up
                    }
                }
            }
            // 完全不存在（包括根目录）：用 normalized 兜底
            return resolved;
        }
    }

    /**
     * 将目标路径解析并验证它在项目目录内（含符号链接解析）。
     */
    private static Path resolveAgainstProject(Path target, Path projectRoot) {
        Path normalized = target.toAbsolutePath().normalize();
        // 先快速检查前缀，降低 toRealPath 调用次数
        if (!normalized.startsWith(projectRoot)) {
            // 可能是符号链接导致的，需要完整解析
            return resolveRealPath(normalized);
        }
        // 前缀匹配，但还需解析符号链接确认（防止项目内 symlink 指到外部）
        return resolveRealPath(normalized);
    }
}
