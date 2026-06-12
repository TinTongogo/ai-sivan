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
 * Agent 路由引擎 — 为任务匹配合适的智能体。
 * <p>
 * 流程：
 * <ol>
 *   <li>查询账户下所有已注册 Agent（用户通过智能体管理页面创建）</li>
 *   <li>无 Agent → 自动创建"通用助手"作为兜底，后续用户可在管理页面添加更多角色</li>
 *   <li>单个 Agent → 直接使用</li>
 *   <li>多个 Agent → 多策略评分融合，选置信度最高的 Agent</li>
 *   <li>置信度不足 → 回退到"通用助手"</li>
 * </ol>
 * <p>
 * Agent 的创建和管理通过智能体管理页面（AgentController）完成，
 * 创建时使用 {@link com.icusu.sivan.agent.prompt.AgentPrompts#AGENT_CREATE_SYSTEM} 等提示词
 * 生成可复用的角色配置（不包含具体任务内容）。
 */
@Slf4j
@Component
public class RoutingEngine {

    private static final double CONFIDENCE_THRESHOLD = 0.65;
    private static final String FALLBACK_AGENT_NAME = "通用助手";

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
                        // 无 Agent → 创建通用助手兜底
                        return Mono.fromCallable(() -> ensureFallbackAgent(accountId));
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

    /**
     * 确保账户有兜底 Agent。如无任何 Agent，创建"通用助手"。
     * @return Agent 名称
     */
    private String ensureFallbackAgent(UUID accountId) {
        List<AgentDefinition> existing = agentRepository.findAllByAccount(accountId);
        if (!existing.isEmpty()) {
            return existing.getFirst().getAgentName();
        }
        AgentDefinition fallback = AgentDefinition.builder()
                .accountId(accountId)
                .agentName(FALLBACK_AGENT_NAME)
                .displayName(FALLBACK_AGENT_NAME)
                .description("可处理各类通用任务，无特定领域限制")
                .category("general")
                .systemPrompt("你是一位通用的智能助手，擅长理解用户意图并完成各类任务。")
                .createdBy("system")
                .build();
        fallback.activate();
        agentRepository.save(fallback);
        log.info("已创建兜底 Agent: accountId={}", accountId.toString().substring(0, 8));
        return FALLBACK_AGENT_NAME;
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
        }
        String lastError = errors.isEmpty() ? null : String.join("; ", errors);

        if (bestResult == null || bestResult.getConfidence() < CONFIDENCE_THRESHOLD) {
            log.info("策略融合置信度不足 ({}), 回退到通用助手",
                    bestResult != null ? bestResult.getConfidence() : 0);
            return ensureFallbackAgent(accountId);
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
