package com.icusu.sivan.domain.goal;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 产物记录 — Task 执行完成后 output/ 目录中新增的文件。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GoalArtifact {

    private UUID artifactId;
    private UUID goalId;
    private int milestoneOrder;
    private int taskOrder;
    private String filePath;
    private String fileType;
    private String summary;
    private long fileSize;
    private LocalDateTime createdAt;
}
