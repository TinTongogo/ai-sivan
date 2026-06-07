package com.icusu.sivan.web.goal.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.UUID;

/** 目标进度响应 DTO（轻量，仅前端 PipelineDialog 展示用）。 */
@Data
@Builder
@AllArgsConstructor
public class GoalProgressResponse {
    private UUID goalId;
    private String title;
    private String status;
    private int totalTasks;
    private int completedTasks;
    private int currentMilestone;
    private List<MilestoneProgress> milestones;

    @Data
    @Builder
    @AllArgsConstructor
    public static class MilestoneProgress {
        private String name;
        private int order;
        /** completed / current / pending */
        private String status;
        private int taskCount;
        private int completedTaskCount;
    }
}
