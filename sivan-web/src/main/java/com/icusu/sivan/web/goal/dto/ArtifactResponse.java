package com.icusu.sivan.web.goal.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

/** 产物响应 DTO。 */
@Data
@Builder
@AllArgsConstructor
public class ArtifactResponse {
    private UUID artifactId;
    private String filePath;
    private String fileType;
    private String summary;
    private long fileSize;
    private LocalDateTime createdAt;
}
