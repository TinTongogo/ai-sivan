package com.icusu.sivan.orch.executor;

import java.util.List;

/**
 * 阶段间调度计划：按序执行的分组列表。
 * <p>
 * 各组之间依次执行（前一组全部完成后才执行下一组），
 * 组内由 GroupMode 控制 SEQUENTIAL 或 PARALLEL。
 */
public class SchedulePlan {

    private final List<ScheduleGroup> groups;

    public SchedulePlan(List<ScheduleGroup> groups) {
        this.groups = groups;
    }

    public List<ScheduleGroup> getGroups() {
        return groups;
    }

    public boolean isEmpty() {
        return groups == null || groups.isEmpty();
    }
}
