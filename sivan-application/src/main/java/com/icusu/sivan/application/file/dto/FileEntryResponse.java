package com.icusu.sivan.application.file.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

/**
 * 文件系统条目 DTO（目录/文件）。
 */
@Data
@Builder
@AllArgsConstructor
public class FileEntryResponse {
    private String name;
    private boolean directory;
    private long size;
    private long lastModified;
}
