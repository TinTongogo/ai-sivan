package com.icusu.sivan.orch.topology;

import com.icusu.sivan.domain.orchestration.Squad;

/**
 * Squad 匹配规范接口（Specification 模式）。
 * 可组合多个规范，加权评分选择最佳匹配。
 */
public interface SquadMatchSpecification {

    /** 判断 Squad 是否满足匹配条件。 */
    boolean isSatisfiedBy(Squad squad, String taskDescription);

    /** 计算匹配得分 [0.0, 1.0]。 */
    double matchScore(Squad squad, String taskDescription);

    /** 规范名称（用于日志/决策审计）。 */
    String name();
}
