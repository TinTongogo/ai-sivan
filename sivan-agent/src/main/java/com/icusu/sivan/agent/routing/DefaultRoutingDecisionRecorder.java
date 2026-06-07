package com.icusu.sivan.agent.routing;

import com.icusu.sivan.domain.routing.IRoutingDecisionRepository;
import com.icusu.sivan.domain.routing.RoutingDecision;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 默认路由决策记录器。
 * 统一处理决策持久化、字段截断、异常吞没。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultRoutingDecisionRecorder implements RoutingDecisionRecorder {

    private static final int MAX_TASK_DESC_LENGTH = 500;

    private final IRoutingDecisionRepository routingDecisionRepository;

    @Override
    public UUID record(RecordRequest request) {
        try {
            RoutingDecision decision = RoutingDecision.builder()
                    .accountId(request.accountId())
                    .projectId(request.projectId())
                    .conversationId(request.conversationId())
                    .taskDescription(truncateStr(request.taskDescription(), MAX_TASK_DESC_LENGTH))
                    .strategy(request.strategy())
                    .selectedAgentName(request.selectedAgentName())
                    .success(request.success())
                    .confidence(request.confidence())
                    .errorHint(request.errorHint())
                    .reasoning(request.reasoning())
                    .context(request.context())
                    .build();
            routingDecisionRepository.save(decision);
            return decision.getDecisionId();
        } catch (Exception e) {
            log.warn("记录路由决策失败(不影响主流程): {}", e.getMessage());
            return null;
        }
    }

    private static String truncateStr(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
