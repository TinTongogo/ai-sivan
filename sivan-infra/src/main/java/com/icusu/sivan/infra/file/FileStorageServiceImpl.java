package com.icusu.sivan.infra.file;

import com.icusu.sivan.common.exception.ResourceNotFoundException;
import com.icusu.sivan.common.util.OwnershipValidator;
import com.icusu.sivan.domain.file.FileRecord;
import com.icusu.sivan.domain.file.FileStoragePort;
import com.icusu.sivan.domain.file.FileUploadResult;
import com.icusu.sivan.domain.file.IFileRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;

/**
 * 文件存储服务。负责将上传文件保存到磁盘，提供下载和 base64 解析能力。
 * 实现领域层 FileStoragePort。
 */
@Slf4j
@Service
public class FileStorageServiceImpl implements FileStoragePort {

    private final Path basePath;
    private final IFileRepository fileRepository;

    public FileStorageServiceImpl(
            @Value("${sivan.file.storage-path:./uploads}") String storagePath,
            IFileRepository fileRepository) {
        this.basePath = Paths.get(storagePath).normalize().toAbsolutePath();
        this.fileRepository = fileRepository;
        try {
            Files.createDirectories(this.basePath);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建文件存储目录: " + this.basePath, e);
        }
    }

    @Override
    public FileUploadResult store(byte[] content, String originalName, String mimeType, UUID accountId) {
        UUID fileId = UUID.randomUUID();
        String subDir = LocalDate.now(ZoneOffset.UTC).toString();
        Path targetDir = basePath.resolve(subDir);
        Path targetPath = targetDir.resolve(fileId.toString());

        try {
            Files.createDirectories(targetDir);
            Files.write(targetPath, content);
        } catch (IOException e) {
            throw new RuntimeException("文件写入失败: " + fileId, e);
        }

        String detectedMime = mimeType != null ? mimeType : detectMimeType(originalName);
        FileRecord record = FileRecord.builder()
                .fileId(fileId)
                .accountId(accountId)
                .fileName(originalName)
                .mimeType(detectedMime)
                .fileSize((long) content.length)
                .storagePath(subDir + "/" + fileId)
                .build();
        fileRepository.save(record);

        log.debug("文件已存储: fileId={}, name={}, size={}", fileId, originalName, content.length);
        return new FileUploadResult(fileId, originalName, detectedMime, (long) content.length);
    }

    @Override
    public String resolveToBase64(UUID accountId, UUID fileId) {
        FileRecord record = findOwnedFile(accountId, fileId);
        Path filePath = basePath.resolve(record.getStoragePath()).normalize();
        if (!filePath.startsWith(basePath)) {
            throw new SecurityException("路径遍历攻击检测: " + filePath);
        }
        try {
            byte[] bytes = Files.readAllBytes(filePath);
            String base64 = Base64.getEncoder().encodeToString(bytes);
            return "data:" + record.getMimeType() + ";base64," + base64;
        } catch (IOException e) {
            log.error("读取文件失败: fileId={}, path={}", fileId, filePath, e);
            throw new RuntimeException("文件读取失败: " + fileId, e);
        }
    }

    @Override
    public byte[] loadBytes(UUID accountId, UUID fileId) {
        FileRecord record = findOwnedFile(accountId, fileId);
        Path filePath = basePath.resolve(record.getStoragePath()).normalize();
        if (!filePath.startsWith(basePath)) {
            throw new SecurityException("路径遍历攻击检测: " + filePath);
        }
        try {
            return Files.readAllBytes(filePath);
        } catch (IOException e) {
            throw ResourceNotFoundException.notFound("文件", fileId);
        }
    }

    @Override
    public String getMimeType(UUID accountId, UUID fileId) {
        return findOwnedFile(accountId, fileId).getMimeType();
    }

    @Override
    public String readTextContent(UUID accountId, UUID fileId, long maxBytes) {
        FileRecord record = findOwnedFile(accountId, fileId);
        String mime = record.getMimeType();
        if (!isTextMimeType(mime)) {
            return "[" + (mime != null ? mime : "未知类型") + " 文件，不支持文本读取]";
        }
        Path filePath = basePath.resolve(record.getStoragePath()).normalize();
        if (!filePath.startsWith(basePath)) {
            throw new SecurityException("路径遍历攻击检测: " + filePath);
        }
        try {
            if (Files.size(filePath) > maxBytes) {
                return "[文件大小 " + Files.size(filePath) / 1024 + "KB，超过 " + maxBytes / 1024 + "KB 限制，仅截取开头]\n"
                        + Files.readString(filePath, java.nio.charset.StandardCharsets.UTF_8).substring(0, (int) maxBytes);
            }
            return Files.readString(filePath, java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.nio.charset.MalformedInputException e) {
            log.warn("文件非 UTF-8 文本: fileId={}, mime={}", fileId, mime);
            return "[文件包含非文本数据]";
        } catch (IOException e) {
            log.warn("读取文件内容失败: fileId={}", fileId, e);
            return "[文件读取失败]";
        }
    }

    /** 判断 MIME 类型是否可读取为 UTF-8 文本。 */
    private boolean isTextMimeType(String mime) {
        if (mime == null) return false;
        return mime.startsWith("text/")
                || mime.startsWith("application/json")
                || mime.startsWith("application/xml")
                || mime.startsWith("application/javascript")
                || mime.startsWith("application/x-yaml")
                || mime.startsWith("application/toml")
                || mime.startsWith("application/x-sh");
    }

    /** 根据文件名推断 mime type。 */
    private String detectMimeType(String filename) {
        if (filename == null) return "application/octet-stream";
        String lower = filename.toLowerCase();
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".bmp")) return "image/bmp";
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        if (lower.endsWith(".wav")) return "audio/wav";
        if (lower.endsWith(".ogg")) return "audio/ogg";
        if (lower.endsWith(".opus")) return "audio/opus";
        if (lower.endsWith(".flac")) return "audio/flac";
        if (lower.endsWith(".m4a") || lower.endsWith(".aac")) return "audio/mp4";
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".txt") || lower.endsWith(".text")) return "text/plain";
        if (lower.endsWith(".md") || lower.endsWith(".markdown")) return "text/markdown";
        if (lower.endsWith(".csv")) return "text/csv";
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "text/html";
        if (lower.endsWith(".xml")) return "application/xml";
        if (lower.endsWith(".json")) return "application/json";
        if (lower.endsWith(".yaml") || lower.endsWith(".yml")) return "application/x-yaml";
        if (lower.endsWith(".js") || lower.endsWith(".mjs")) return "application/javascript";
        if (lower.endsWith(".ts") || lower.endsWith(".tsx")) return "text/typescript";
        if (lower.endsWith(".py")) return "text/x-python";
        if (lower.endsWith(".java") || lower.endsWith(".kt")) return "text/x-java";
        if (lower.endsWith(".sh") || lower.endsWith(".bash")) return "application/x-sh";
        if (lower.endsWith(".toml")) return "application/toml";
        if (lower.endsWith(".log")) return "text/plain";
        if (lower.endsWith(".sql")) return "text/x-sql";
        return "application/octet-stream";
    }

    /** 查找文件记录并校验所有权。 */
    private FileRecord findOwnedFile(UUID accountId, UUID fileId) {
        return OwnershipValidator.findOwned(accountId, "文件", fileId, fileRepository::findById, FileRecord::getAccountId);
    }
}
