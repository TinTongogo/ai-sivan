package com.icusu.sivan.domain.agent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 项目 — 用户创建的顶层分组，包含对话和文件的归属范围。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Project {

    private UUID projectId;
    private UUID accountId;
    private String name;
    private String description;
    private Integer sortOrder;
    private String localPath;
    @Builder.Default private Boolean undeletable = false;
    @Builder.Default private Boolean archived = false;
    private OffsetDateTime archivedAt;
    @Builder.Default private Boolean localPathAuto = false;
    private String shortId;
}
