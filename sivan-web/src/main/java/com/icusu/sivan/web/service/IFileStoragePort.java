package com.icusu.sivan.web.file.service;

import com.icusu.sivan.web.file.dto.FileUploadResponse;
import org.springframework.core.io.Resource;
import org.springframework.http.codec.multipart.FilePart;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * 文件存储端口。本地文件系统 / MinIO / 其他存储均可实现此接口。
 */
public interface IFileStoragePort {

    /** 保存上传文件。 */
    Mono<FileUploadResponse> store(FilePart filePart, UUID accountId);

    /** 将文件解析为 base64 data URI。 */
    String resolveToBase64(UUID accountId, UUID fileId);

    /** 加载文件为 Resource，供前端下载/预览。 */
    Mono<Resource> loadAsResource(UUID accountId, UUID fileId);

    /** 获取文件的 MIME 类型。 */
    String getMimeType(UUID accountId, UUID fileId);

    /** 读取文本文件内容（UTF-8）。 */
    String readTextContent(UUID accountId, UUID fileId, long maxBytes);
}
