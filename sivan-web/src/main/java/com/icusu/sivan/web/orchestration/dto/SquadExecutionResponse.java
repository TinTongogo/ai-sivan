package com.icusu.sivan.web.orchestration.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Squad 执行响应 DTO。
 */
@Data
@Builder
@AllArgsConstructor
public class SquadExecutionResponse {
    private UUID executionId;
    private UUID squadId;
    private UUID projectId;
    private String taskDescription;
    private String status;
    private String content;
    private String thinking;
    private String topologySnapshot;
    private String squadName;
    private String squadMode;
    private Integer currentPhase;
    private String errorMessage;
    private LocalDateTime startedAt;
    private LocalDateTime pausedAt;
    private LocalDateTime completedAt;
    private LocalDateTime createdAt;
}
