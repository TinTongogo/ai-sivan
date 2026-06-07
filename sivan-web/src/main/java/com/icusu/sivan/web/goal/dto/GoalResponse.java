package com.icusu.sivan.web.goal.dto;

import com.icusu.sivan.domain.goal.Milestone;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** 目标响应 DTO。 */
@Data
@Builder
@AllArgsConstructor
public class GoalResponse {
    private UUID goalId;
    private UUID projectId;
    private UUID conversationId;
    private String title;
    private String description;
    private String status;
    private String autoMode;
    private List<Milestone> milestones;
    private int currentMilestone;
    private int totalTasks;
    private int completedTasks;
    private String pauseReason;
    private String fileRootPath;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime completedAt;
    private LocalDateTime pausedAt;
}
