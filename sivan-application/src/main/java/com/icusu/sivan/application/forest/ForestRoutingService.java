package com.icusu.sivan.application.forest;

import com.icusu.sivan.agent.routing.RoutingDecisionRecorder;
import com.icusu.sivan.infra.routing.RouteFeedbackHandler;
import com.icusu.sivan.agent.routing.engine.PgRouteEngine;
import com.icusu.sivan.domain.memory.TaskFeatures;
import com.icusu.sivan.domain.routing.RouteResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.UUID;

/**
 * 森林路由服务 — 路由决策 + 反馈记录。
 * <p>
 * 将路由逻辑从 ForestConversationService 拆分出来，职责单一。
 */
@Service
public class ForestRoutingService {

    private static final Logger log = LoggerFactory.getLogger(ForestRoutingService.class);

    private final PgRouteEngine pgRouteEngine;
    private final RoutingDecisionRecorder routingDecisionRecorder;
    private final RouteFeedbackHandler routeFeedbackHandler;

    public ForestRoutingService(PgRouteEngine pgRouteEngine,
                                RoutingDecisionRecorder routingDecisionRecorder,
                                RouteFeedbackHandler routeFeedbackHandler) {
        this.pgRouteEngine = pgRouteEngine;
        this.routingDecisionRecorder = routingDecisionRecorder;
        this.routeFeedbackHandler = routeFeedbackHandler;
    }

    /**
     * 为任务内容执行路由决策。
     *
     * @return RouteResult，内含 agentName/intent/tier/confidence/matchedSkillIds
     */
    public RouteResult resolve(UUID accountId, String taskContent) {
        String featureHash = TaskFeatures.fromContent(taskContent) != null
                ? PgRouteEngine.md5(TaskFeatures.fromContent(taskContent).toString())
                : "";
        try {
            return pgRouteEngine.resolve(accountId, taskContent, featureHash)
                    .subscribeOn(Schedulers.boundedElastic())
                    .block(Duration.ofSeconds(30));
        } catch (Exception e) {
            log.warn("[路由] PgRouteEngine 异常: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 记录路由决策（仅 task 意图）。
     */
    public void recordIfTask(UUID accountId, UUID conversationId, String userContent,
                             RouteResult routeResult) {
        if (routeResult == null || !"task".equals(routeResult.intent())) return;
        try {
            routingDecisionRecorder.record(new RoutingDecisionRecorder.RecordRequest(
                    accountId, null, conversationId, userContent,
                    routeTierToStrategy(routeResult.tier()), routeResult.agentName(), true,
                    "Tier " + routeResult.tier() + " · 置信度 "
                            + String.format("%.0f", routeResult.confidence() * 100) + "%",
                    routeResult.confidence(), null, null));
        } catch (Exception e) {
            log.warn("[路由] 记录路由决策异常: {}", e.getMessage());
        }
    }

    /** 记录节点执行反馈（更新 Beta 参数 + embedding）。 */
    public void recordFeedback(UUID accountId, String agentName, String taskContent,
                               boolean success, String routeTier) {
        if (accountId == null || agentName == null || taskContent == null) return;
        try {
            routeFeedbackHandler.onNodeCompleted(accountId, agentName, taskContent, success, routeTier);
        } catch (Exception e) {
            log.warn("[路由] 记录反馈异常: {}", e.getMessage());
        }
    }

    private static String routeTierToStrategy(int tier) {
        return switch (tier) {
            case 0 -> "exact";
            case 1 -> "semantic";
            case 2 -> "explore";
            case 3 -> "auto_create";
            default -> "auto";
        };
    }
}
