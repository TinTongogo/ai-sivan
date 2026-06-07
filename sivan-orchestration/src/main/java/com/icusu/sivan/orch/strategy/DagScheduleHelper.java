package com.icusu.sivan.orch.strategy;

import com.icusu.sivan.domain.orchestration.ContextPackage;
import com.icusu.sivan.domain.orchestration.PhaseNode;

import java.util.List;

/**
 * DAG 入度调度工具。提供 dependsOn 就绪检查。
 *
 * <p>各策略在执行阶段前调用 {@link #isReady(PhaseNode, ContextPackage)}：
 * <ul>
 *   <li>dependsOn=null → 由 SquadMode 隐式推断（100% 向后兼容）</li>
 *   <li>dependsOn=[] → 无依赖，始终就绪</li>
 *   <li>dependsOn=[0,1] → 等阶段 0 和 1 都完成后才就绪</li>
 * </ul>
 */
public final class DagScheduleHelper {

    private DagScheduleHelper() {}

    /**
     * 检查阶段是否已就绪（所有依赖阶段已完成）。
     */
    public static boolean isReady(PhaseNode phase, ContextPackage context) {
        List<Integer> deps = phase.getDependsOn();
        if (deps == null || deps.isEmpty()) return true;
        return deps.stream().allMatch(depIdx -> context.getPhaseOutputs().containsKey(depIdx));
    }

    /**
     * 检查阶段是否有显式 dependsOn 声明。
     */
    public static boolean hasExplicitDependencies(PhaseNode phase) {
        return phase.getDependsOn() != null;
    }
}
