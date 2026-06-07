package com.icusu.sivan.domain.goal;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

/**
 * 任务（领域实体）— 存储在 goal_tasks 独立表。
 * <p>taskId 由数据库生成，milestoneId 关联所属里程碑。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Task {

    private UUID taskId;
    private UUID milestoneId;
    private int order;
    private String description;
    /** 在 Phase 中的 Agent 索引。 */
    private int agentIndex;
    /** Agent 名称。 */
    private String agentName;
    private boolean completed;
    private String artifactSummary;
    private String taskRef;
    private String status;
    private String inputArtifact;
    private List<String> outputFiles;
}
