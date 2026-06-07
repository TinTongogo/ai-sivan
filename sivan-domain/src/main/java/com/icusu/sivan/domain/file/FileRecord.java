package com.icusu.sivan.domain.file;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 上传文件记录。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileRecord {

    private UUID fileId;
    private UUID accountId;
    private String fileName;
    private String mimeType;
    private Long fileSize;
    private String storagePath;
    private LocalDateTime createdAt;
}
