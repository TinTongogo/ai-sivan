package com.icusu.sivan.orch.executor;

import com.icusu.sivan.orch.topology.SquadMatchSpecification;
import com.icusu.sivan.domain.orchestration.Squad;

import java.util.List;

/**
 * Squad 匹配器接口。
 * 根据用户任务描述，从候选 Squad 列表中选出最匹配的 Squad。
 *
 * <p>能力边界：
 * <ul>
 *   <li>多维度加权匹配（名称、关键词、语义等）</li>
 *   <li>返回最佳匹配或 null（无匹配时由调用方自动创建）</li>
 * </ul>
 *
 * @see SquadMatchSpecification
 * @see CompositeSquadMatcher
 */
public interface SquadMatcher {

    /**
     * 从候选列表中选出最佳匹配的 Squad。
     *
     * @param taskDescription 用户任务描述
     * @param candidates      候选 Squad 列表
     * @return 匹配的 Squad，无匹配时返回 null
     */
    Squad match(String taskDescription, List<Squad> candidates);
}
