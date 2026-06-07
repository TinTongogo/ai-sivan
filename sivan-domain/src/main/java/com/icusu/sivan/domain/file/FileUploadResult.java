package com.icusu.sivan.domain.file;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.util.UUID;

/**
 * 文件上传结果值对象。
 */
@Data
@Builder
@AllArgsConstructor
public class FileUploadResult {
    private UUID fileId;
    private String fileName;
    private String mimeType;
    private Long fileSize;
}
