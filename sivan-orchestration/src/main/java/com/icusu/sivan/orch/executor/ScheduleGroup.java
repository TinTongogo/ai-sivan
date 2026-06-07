package com.icusu.sivan.orch.executor;

import com.icusu.sivan.domain.orchestration.PhaseNode;

import java.util.List;

/**
 * 调度分组：一组按 GroupMode 编排的阶段。
 * <p>
 * 例如 SquadMode=SEQUENTIAL 对应一个 SEQUENTIAL 分组包含所有阶段；
 * SquadMode=PARALLEL 对应一个 PARALLEL 分组包含所有阶段；
 * CONDITIONAL/HIERARCHICAL 可分为规划分组 + 执行分组。
 */
public class ScheduleGroup {

    private final GroupMode mode;
    private final List<PhaseNode> phases;

    public ScheduleGroup(GroupMode mode, List<PhaseNode> phases) {
        this.mode = mode;
        this.phases = phases;
    }

    public GroupMode getMode() {
        return mode;
    }

    public List<PhaseNode> getPhases() {
        return phases;
    }
}
