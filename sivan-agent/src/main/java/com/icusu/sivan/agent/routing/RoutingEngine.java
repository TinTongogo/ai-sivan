package com.icusu.sivan.agent.routing;

import com.icusu.sivan.domain.agent.AgentDefinition;
import com.icusu.sivan.domain.agent.IAgentRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 四策略路由引擎（全响应式，零阻塞）。
 */
@Slf4j
@Component
public class RoutingEngine {

    private static final double CONFIDENCE_THRESHOLD = 0.65;
    private final List<RoutingStrategy> strategies;
    private final IAgentRepository agentRepository;
    private final RoutingDecisionRecorder routingDecisionRecorder;

    public RoutingEngine(List<RoutingStrategy> strategies,
                         IAgentRepository agentRepository,
                         RoutingDecisionRecorder routingDecisionRecorder) {
        this.strategies = strategies;
        this.agentRepository = agentRepository;
        this.routingDecisionRecorder = routingDecisionRecorder;
    }

    public Mono<String> resolve(String taskDescription, UUID accountId, UUID conversationId) {
        return Mono.fromCallable(() -> agentRepository.findAllByAccount(accountId))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(agents -> {
                    if (agents.isEmpty()) {
                        log.warn("无可用的 Agent，accountId={}", accountId);
                        return Mono.empty();
                    }
                    if (agents.size() == 1) {
                        AgentDefinition solo = agents.getFirst();
                        routingDecisionRecorder.record(new RoutingDecisionRecorder.RecordRequest(
                                accountId, null, conversationId, taskDescription,
                                "auto", solo.getAgentName(), true,
                                "唯一可用 Agent", 1.0, null,
                                buildContext(taskDescription, accountId, agents.size(), "auto", null, null)));
                        return Mono.just(solo.getAgentName());
                    }

                    return Flux.fromIterable(strategies)
                            .flatMap(strategy -> strategy.route(taskDescription, agents, accountId)
                                    .onErrorResume(e -> {
                                        log.warn("策略 {} 执行异常: {}", strategy.name(), e.getMessage());
                                        return Mono.just(RoutingResult.builder()
                                                .strategyName(strategy.name())
                                                .confidence(0.0)
                                                .reasoning("策略异常")
                                                .errorDetail(e.getMessage())
                                                .build());
                                    }))
                            .collectList()
                            .mapNotNull(results -> selectBest(results, taskDescription, accountId, conversationId, agents.size()));
                });
    }

    private String selectBest(List<RoutingResult> results, String taskDescription, UUID accountId,
                               UUID conversationId, int agentCount) {
        RoutingResult bestResult = null;
        List<String> errors = new ArrayList<>();
        for (RoutingResult result : results) {
            if (result == null) continue;
            if (result.getErrorDetail() != null) {
                errors.add(result.getStrategyName() + ": " + result.getErrorDetail());
            }
            if (result.getSelectedAgent() == null) continue;
            if (bestResult == null || result.getConfidence() > bestResult.getConfidence()) {
                bestResult = result;
            }
            log.debug("策略 {} 结果: agent={}, confidence={}", result.getStrategyName(), result.getSelectedAgent(), result.getConfidence());
        }
        String lastError = errors.isEmpty() ? null : String.join("; ", errors);

        if (bestResult == null || bestResult.getConfidence() < CONFIDENCE_THRESHOLD) {
            log.info("策略融合置信度不足 ({}), 触发自动创建",
                    bestResult != null ? bestResult.getConfidence() : 0);
            routingDecisionRecorder.record(new RoutingDecisionRecorder.RecordRequest(
                    accountId, null, conversationId, taskDescription,
                    "fallback", "AUTO_CREATE", false,
                    "全策略置信度不足，触发自动创建", 0.0, lastError,
                    buildContext(taskDescription, accountId, agentCount, "fallback", null, lastError)));
            return null;
        }

        routingDecisionRecorder.record(new RoutingDecisionRecorder.RecordRequest(
                accountId, null, conversationId, taskDescription,
                bestResult.getStrategyName(), bestResult.getSelectedAgent(), true,
                bestResult.getReasoning(), bestResult.getConfidence(), lastError,
                buildContext(taskDescription, accountId, agentCount, bestResult.getStrategyName(), bestResult.getReasoning(), lastError)));

        return bestResult.getSelectedAgent();
    }

    private Map<String, Object> buildContext(String taskDescription, UUID accountId, int agentCount,
                                              String strategyName, String reasoning, String error) {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("strategy", strategyName);
        ctx.put("agentCount", agentCount);
        ctx.put("inputType", classifyInputType(taskDescription));
        ctx.put("needsSandbox", false);
        if (reasoning != null) ctx.put("reasoning", reasoning);
        if (error != null) ctx.put("error", error);
        return ctx;
    }

    private String classifyInputType(String taskDescription) {
        if (taskDescription == null || taskDescription.isBlank()) return "chat";
        String lower = taskDescription.toLowerCase();
        for (String kw : TASK_KEYWORDS) {
            if (lower.contains(kw)) return "task";
        }
        return "chat";
    }

    private static final List<String> TASK_KEYWORDS = List.of(
            "分析", "处理", "生成", "创建", "执行", "查找", "计算", "翻译", "总结",
            "提取", "转换", "合并", "拆分", "比较", "统计", "预测", "优化",
            "analyze", "process", "generate", "create", "execute", "find", "calculate",
            "translate", "summarize", "extract", "convert", "merge", "split", "compare"
    );
}
