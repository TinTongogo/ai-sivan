package com.icusu.sivan.domain.file;

import java.util.UUID;

/**
 * 文件存储端口。本地文件系统 / MinIO / 其他存储均可实现此接口。
 * 领域层端口，零框架依赖。
 */
public interface FileStoragePort {

    /** 保存上传文件。 */
    FileUploadResult store(byte[] content, String originalName, String mimeType, UUID accountId);

    /** 将文件解析为 base64 data URI。 */
    String resolveToBase64(UUID accountId, UUID fileId);

    /** 加载文件内容字节数组，供前端下载/预览。 */
    byte[] loadBytes(UUID accountId, UUID fileId);

    /** 获取文件的 MIME 类型。 */
    String getMimeType(UUID accountId, UUID fileId);

    /** 读取文本文件内容（UTF-8）。 */
    String readTextContent(UUID accountId, UUID fileId, long maxBytes);
}
