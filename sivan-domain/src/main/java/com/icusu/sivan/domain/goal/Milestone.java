package com.icusu.sivan.domain.goal;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

/**
 * 里程碑（领域实体）— 存储在 goal_milestones 独立表。
 * <p>milestoneId 由数据库生成，goalId 关联所属目标。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Milestone {

    private UUID milestoneId;
    private UUID goalId;
    private String name;
    private String description;
    private int order;
    /** 对应的 Phase 索引（Squad 拓扑中的位置）。 */
    private int phaseIndex;
    /** Phase 的 mode (SEQUENTIAL/PARALLEL/...)。 */
    private String phaseMode;
    private List<Task> tasks;
}
