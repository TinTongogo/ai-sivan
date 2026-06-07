package com.icusu.sivan.agent.routing.strategy;

import com.icusu.sivan.agent.routing.RoutingResult;
import com.icusu.sivan.agent.routing.RoutingStrategy;
import com.icusu.sivan.domain.agent.AgentDefinition;
import com.icusu.sivan.domain.routing.IRoutingDecisionRepository;
import com.icusu.sivan.domain.routing.RoutingDecision;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 自适应路由策略。
 * 基于历史路由决策的成功率来选择最可靠的 Agent。
 * 优先选择历史成功率高的 Agent。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdaptiveRoutingStrategy implements RoutingStrategy {

    private final IRoutingDecisionRepository routingDecisionRepository;

    /**
     * 返回策略名称。
     */
    @Override
    public String name() {
        return "adaptive";
    }

    /**
     * 执行自适应路由决策。
     */
    @Override
    public Mono<RoutingResult> route(String taskDescription, List<AgentDefinition> agents, UUID accountId) {
        if (agents.isEmpty()) {
            return Mono.just(RoutingResult.builder()
                    .strategyName(name()).selectedAgent(null)
                    .confidence(0.0).reasoning("无可用 Agent").build());
        }
        if (agents.size() == 1) {
            AgentDefinition solo = agents.getFirst();
            return Mono.just(RoutingResult.builder()
                    .strategyName(name()).selectedAgent(solo.getAgentName())
                    .confidence(0.7).reasoning("唯一可用 Agent（自适应历史）").build());
        }

        return Mono.fromCallable(() -> {
            // 获取历史决策记录
            List<RoutingDecision> history = routingDecisionRepository.findByAccount(accountId);

            // 按 Agent 名称分组统计
            Map<String, List<RoutingDecision>> byAgent = history.stream()
                    .filter(d -> d.getSelectedAgentName() != null)
                    .collect(Collectors.groupingBy(RoutingDecision::getSelectedAgentName));

            Set<String> availableNames = agents.stream()
                    .map(AgentDefinition::getAgentName).collect(Collectors.toSet());

        String bestAgent = null;
        double bestScore = -1.0;
        String bestReason = null;

        for (AgentDefinition agent : agents) {
            List<RoutingDecision> agentHistory = byAgent.get(agent.getAgentName());
            if (agentHistory == null || agentHistory.isEmpty()) {
                // 无历史记录，中等分数
                double score = 0.4;
                if (bestAgent == null || score > bestScore) {
                    bestScore = score;
                    bestAgent = agent.getAgentName();
                    bestReason = "无历史记录，默认中等置信度";
                }
                continue;
            }

            long total = agentHistory.size();
            long successCount = agentHistory.stream()
                    .filter(d -> Boolean.TRUE.equals(d.getSuccess()))
                    .count();
            double successRate = (double) successCount / total;

            // 综合分数 = 成功率 * 0.7 + 样本量因子 * 0.3
            double sampleFactor = Math.min((double) total / 10.0, 1.0); // 10 次样本达到满分
            double score = successRate * 0.7 + sampleFactor * 0.3;

            String reason = String.format("历史成功率 %.0f%% (%d/%d), 样本量 %d",
                    successRate * 100, successCount, total, total);

            // 领域不匹配惩罚：任务描述与 Agent 名称/描述无词汇重叠时降权
            String task = taskDescription.toLowerCase();
            String agentContext = (agent.getAgentName() + " "
                    + (agent.getDescription() != null ? agent.getDescription() : "")).toLowerCase();
            String[] taskWords = task.split("[\\s,，、。.？?!！]+");
            boolean hasOverlap = false;
            for (String w : taskWords) {
                if (w.length() > 1 && agentContext.contains(w)) {
                    hasOverlap = true;
                    break;
                }
            }
            if (!hasOverlap) {
                score *= 0.5;
                reason += ", 领域不匹配(降权×0.5)";
            }

            if (score > bestScore) {
                bestScore = score;
                bestAgent = agent.getAgentName();
                bestReason = reason;
            }
        }

        if (bestAgent == null) {
            bestAgent = agents.get(0).getAgentName();
            bestReason = "无历史数据，默认选择";
        }

        log.debug("Adaptive路由: 选中={}, 分数={}, 理由={}", bestAgent, bestScore, bestReason);

            return RoutingResult.builder()
                    .strategyName(name()).selectedAgent(bestAgent)
                    .confidence(Math.min(bestScore, 0.95)).reasoning(bestReason).build();
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
