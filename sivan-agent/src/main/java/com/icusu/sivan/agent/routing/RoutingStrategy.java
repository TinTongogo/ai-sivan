package com.icusu.sivan.agent.routing;

import com.icusu.sivan.domain.agent.AgentDefinition;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 路由策略接口。
 * 每种策略根据任务描述和可用 Agent 列表，输出路由结果。
 */
public interface RoutingStrategy {

    /**
     * 策略名称，用于记录和调试。
     */
    String name();

    /**
     * 执行路由决策。
     *
     * @param taskDescription 用户任务描述
     * @param agents          可用 Agent 列表
     * @param accountId       用户账号 ID
     * @return 路由结果（含选中 Agent、置信度、理由）
     */
    Mono<RoutingResult> route(String taskDescription, List<AgentDefinition> agents, java.util.UUID accountId);
}
