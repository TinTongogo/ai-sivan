package com.icusu.sivan.agent.routing.strategy;

import com.icusu.sivan.agent.routing.RoutingResult;
import com.icusu.sivan.agent.routing.RoutingStrategy;
import com.icusu.sivan.common.enums.AgentType;
import com.icusu.sivan.domain.agent.AgentDefinition;
import com.icusu.sivan.domain.shared.port.IEmbeddingService;
import com.icusu.sivan.common.util.CosineSimilarity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.UUID;

/**
 * ML 路由策略（关键词规则 + Embedding 语义匹配）。
 * 优先使用 EmbeddingService 计算任务与 Agent 的语义相似度；
 * Embedding 不可用时降级为关键词规则匹配（名称匹配、描述关键词、类型偏好、类别匹配）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MLRoutingStrategy implements RoutingStrategy {

    private final IEmbeddingService embeddingService;

    /**
     * 返回策略名称。
     */
    @Override
    public String name() {
        return "ml";
    }

    /**
     * 执行路由决策：Embedding 语义匹配 → 关键词规则降级。
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
                    .confidence(0.6).reasoning("唯一可用 Agent（规则匹配）").build());
        }

        // 优先尝试 Embedding 语义匹配
        return Mono.fromCallable(() -> {
            try {
                return embedRoute(taskDescription, agents);
            } catch (Exception e) {
                log.debug("Embedding 服务不可用，降级为关键词规则匹配: {}", e.getMessage());
                return keywordRoute(taskDescription, agents);
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /** Embedding 语义匹配分支。 */
    private RoutingResult embedRoute(String taskDescription, List<AgentDefinition> agents) {
        float[] taskVec = embeddingService.embed(taskDescription);

        // 批量 embedding 所有 agent 画像，避免逐个 HTTP 请求
        List<String> profiles = agents.stream()
                .map(a -> a.getAgentName() + ": " + (a.getDescription() != null ? a.getDescription() : ""))
                .toList();
        List<float[]> agentVecs = embeddingService.embedBatch(profiles);

        AgentDefinition bestMatch = null;
        double bestScore = 0.0;
        StringBuilder bestReason = new StringBuilder();

        for (int i = 0; i < agents.size() && i < agentVecs.size(); i++) {
            float[] agentVec = agentVecs.get(i);
            if (agentVec == null) continue;
            AgentDefinition agent = agents.get(i);
            double similarity = CosineSimilarity.compute(taskVec, agentVec);

            if (similarity > bestScore) {
                bestScore = similarity;
                bestMatch = agent;
                bestReason = new StringBuilder(String.format("语义匹配相似度 %.2f", similarity));
            }
        }

        if (bestMatch == null) {
            bestMatch = agents.getFirst();
            bestReason = new StringBuilder("Embedding 匹配无结果，默认选择");
        }

        // similarity 范围 0~1，映射到 confidence
        double confidence = Math.min(bestScore * 0.9, 0.85);
        log.debug("ML路由(Embedding): 选中={}, 相似度={}, 置信度={}",
                bestMatch.getAgentName(), String.format("%.4f", bestScore), String.format("%.4f", confidence));

        return RoutingResult.builder()
                .strategyName(name()).selectedAgent(bestMatch.getAgentName())
                .confidence(confidence).reasoning(bestReason.toString().trim()).build();
    }

    /** 关键词规则匹配分支（原逻辑）。 */
    private RoutingResult keywordRoute(String taskDescription, List<AgentDefinition> agents) {
        String task = taskDescription.toLowerCase();
        AgentDefinition bestMatch = null;
        double bestScore = 0.0;
        StringBuilder bestReason = new StringBuilder();

        for (AgentDefinition agent : agents) {
            double score = 0.0;
            StringBuilder reason = new StringBuilder();

            // 1. 名称匹配：Agent 名出现在任务描述中
            if (agent.getAgentName() != null && task.contains(agent.getAgentName().toLowerCase())) {
                score += 0.4;
                reason.append("名称匹配; ");
            }

            // 2. 描述关键词匹配
            if (agent.getDescription() != null) {
                String desc = agent.getDescription().toLowerCase();
                String[] keywords = task.split("[\\s,，、。.]+");
                long matchCount = 0;
                for (String kw : keywords) {
                    if (kw.length() > 1 && desc.contains(kw)) {
                        matchCount++;
                    }
                }
                double keywordScore = Math.min(matchCount * 0.15, 0.45);
                if (keywordScore > 0) {
                    score += keywordScore;
                    reason.append("描述匹配 ").append(matchCount).append(" 个关键词; ");
                }
            }

            // 3. 类型偏好：USER 类型 Agent 更高优先级
            if (AgentType.SYSTEM.equals(agent.getAgentType())) {
                score += 0.1;
                reason.append("系统 Agent; ");
            }

            // 4. category 匹配
            if (agent.getCategory() != null && task.contains(agent.getCategory().toLowerCase())) {
                score += 0.2;
                reason.append("类别匹配; ");
            }

            if (score > bestScore) {
                bestScore = score;
                bestMatch = agent;
                bestReason = new StringBuilder(reason);
            }
        }

        if (bestMatch == null) {
            bestMatch = agents.getFirst();
            bestReason = new StringBuilder("无规则匹配，默认选择");
        }

        log.debug("ML路由(关键词): 选中={}, 分数={}, 理由={}", bestMatch.getAgentName(), bestScore, bestReason);

        return RoutingResult.builder()
                .strategyName(name()).selectedAgent(bestMatch.getAgentName())
                .confidence(Math.min(bestScore, 0.95)).reasoning(bestReason.toString().trim()).build();
    }
}