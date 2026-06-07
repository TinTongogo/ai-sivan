package com.icusu.sivan.orch.topology;

import com.icusu.sivan.domain.task.ExecutionPath;
import com.icusu.sivan.domain.task.TaskFeatures;

/**
 * 基于 TaskFeatures 五维特征的执行模式选择器。
 *
 * <p>规则：
 * <ul>
 *   <li>LEVEL_1 → CHAT（无论其他维度）</li>
 *   <li>LEVEL_2 + INDEPENDENT → SINGLE_AGENT</li>
 *   <li>LEVEL_2 + CONDITIONAL → SQUAD + CONDITIONAL</li>
 *   <li>LEVEL_3 → SQUAD + SEQUENTIAL（默认）</li>
 *   <li>LEVEL_4 → SQUAD + HIERARCHICAL</li>
 *   <li>LEVEL_5 → SQUAD + HIERARCHICAL + 阶段内 PARALLEL</li>
 * </ul>
 */
public class ExecutionModeSelector {

    /**
     * 推荐执行路径。结果包含 shape、squadMode、phaseMode、reason。
     */
    public static ExecutionPath recommend(TaskFeatures features) {
        if (features == null) {
            return ExecutionPath.squad("SEQUENTIAL", "SEQUENTIAL", null, "特征为空，默认 SQUAD");
        }

        TaskFeatures.Complexity c = features.complexity() != null ? features.complexity() : TaskFeatures.Complexity.LEVEL_2;
        TaskFeatures.Dependency d = features.dependency() != null ? features.dependency() : TaskFeatures.Dependency.INDEPENDENT;

        return switch (c) {
            case LEVEL_1 -> ExecutionPath.chat("复杂度 LEVEL_1，直接聊天");

            case LEVEL_2 -> switch (d) {
                case INDEPENDENT, SEQUENTIAL, PARALLEL ->
                        ExecutionPath.singleAgent("复杂度 LEVEL_2，单一智能体即可");
                case CONDITIONAL ->
                        ExecutionPath.squad("CONDITIONAL", "SEQUENTIAL", null,
                                "复杂度 LEVEL_2 + 条件分支，SQUAD + CONDITIONAL");
            };

            case LEVEL_3 -> switch (d) {
                case CONDITIONAL ->
                        ExecutionPath.squad("CONDITIONAL", "SEQUENTIAL", null,
                                "复杂度 LEVEL_3 + 条件分支，SQUAD + CONDITIONAL");
                case PARALLEL ->
                        ExecutionPath.squad("PARALLEL", "SEQUENTIAL", null,
                                "复杂度 LEVEL_3 + 可并行，SQUAD + PARALLEL");
                default ->
                        ExecutionPath.squad("SEQUENTIAL", "SEQUENTIAL", null,
                                "复杂度 LEVEL_3，SQUAD + SEQUENTIAL");
            };

            case LEVEL_4 -> switch (d) {
                case CONDITIONAL ->
                        ExecutionPath.squad("CONDITIONAL", "SEQUENTIAL", null,
                                "复杂度 LEVEL_4 + 条件分支，SQUAD + CONDITIONAL");
                case PARALLEL ->
                        ExecutionPath.squad("HIERARCHICAL", "PARALLEL", null,
                                "复杂度 LEVEL_4 + 可并行，HIERARCHICAL + 阶段内 PARALLEL");
                default ->
                        ExecutionPath.squad("HIERARCHICAL", "SEQUENTIAL", null,
                                "复杂度 LEVEL_4，SQUAD + HIERARCHICAL");
            };

            case LEVEL_5 -> ExecutionPath.squad("HIERARCHICAL", "PARALLEL", null,
                    "复杂度 LEVEL_5，HIERARCHICAL + 阶段内 PARALLEL");
        };
    }
}
