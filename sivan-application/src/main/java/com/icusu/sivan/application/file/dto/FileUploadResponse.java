package com.icusu.sivan.application.file.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.UUID;

/**
 * 文件上传响应 DTO。
 */
@Data
@AllArgsConstructor
public class FileUploadResponse {
    private UUID fileId;
    private String fileName;
    private String mimeType;
    private Long fileSize;
}
