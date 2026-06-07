package com.icusu.sivan.agent.routing;

import com.icusu.sivan.domain.routing.RoutingDecision;

import java.util.Map;
import java.util.UUID;

/**
 * 路由决策记录器接口，统一各执行路径中的路由决策持久化。
 * <p>
 * 消除 {@code routingDecisionRepository.save()} 在 SingleAgentStrategy、
 * SquadOrchestrator、RoutingEngine 中的重复调用。
 */
public interface RoutingDecisionRecorder {

    /**
     * 记录路由决策。
     *
     * @param request 决策记录参数
     * @return 决策 ID，失败时返回 null
     */
    UUID record(RecordRequest request);

    /** 路由决策记录参数。 */
    record RecordRequest(
            UUID accountId,
            UUID projectId,
            UUID conversationId,
            String taskDescription,
            String strategy,
            String selectedAgentName,
            boolean success,
            String reasoning,
            Double confidence,
            String errorHint,
            Map<String, Object> context
    ) {
        /** 精简构造（不含 confidence/errorHint 的场景）。 */
        public static RecordRequest simple(
                UUID accountId, UUID projectId, UUID conversationId,
                String taskDescription, String strategy, String selectedAgentName,
                boolean success, String reasoning, Map<String, Object> context) {
            return new RecordRequest(accountId, projectId, conversationId,
                    taskDescription, strategy, selectedAgentName,
                    success, reasoning, null, null, context);
        }

        /** 从已有的 RoutingDecision 实体转换（用于 RoutingEngine 场景）。 */
        public static RecordRequest from(RoutingDecision decision) {
            return new RecordRequest(
                    decision.getAccountId(), decision.getProjectId(), decision.getConversationId(),
                    decision.getTaskDescription(), decision.getStrategy(), decision.getSelectedAgentName(),
                    decision.getSuccess() != null && decision.getSuccess(),
                    decision.getReasoning(), decision.getConfidence(), decision.getErrorHint(),
                    decision.getContext());
        }
    }
}
