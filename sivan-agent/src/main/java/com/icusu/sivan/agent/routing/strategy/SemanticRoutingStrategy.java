package com.icusu.sivan.agent.routing.strategy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.icusu.sivan.agent.prompt.ChatPrompts;
import com.icusu.sivan.agent.routing.RoutingResult;
import com.icusu.sivan.agent.routing.RoutingStrategy;
import com.icusu.sivan.agent.service.LlmService;
import com.icusu.sivan.domain.agent.AgentDefinition;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 语义路由策略。
 * 通过 LLM 理解任务语义，匹配最合适的 Agent。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SemanticRoutingStrategy implements RoutingStrategy {

    private final LlmService llmService;
    private final ObjectMapper objectMapper;

    /**
     * 返回策略名称。
     */
    @Override
    public String name() {
        return "semantic";
    }

    /**
     * 执行语义路由决策（响应式）。
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
                    .confidence(1.0).reasoning("唯一可用 Agent").build());
        }

        String agentList = agents.stream()
                .map(a -> "- " + a.getAgentName() + ": "
                        + (a.getDescription() != null ? a.getDescription() : "无描述")
                        + " [类型: " + a.getAgentType() + "]")
                .collect(Collectors.joining("\n"));

        String userPrompt = ChatPrompts.semanticRouteUser(agentList, taskDescription).content();

        return llmService.chat(ChatPrompts.SEMANTIC_ROUTE_SYSTEM.content(), userPrompt, accountId)
                .map(response -> {
                    RoutingResult parsed = parseRoutingResponse(response);
                    if (parsed == null) {
                        log.warn("LLM 返回解析失败，回退到首个 Agent");
                        return RoutingResult.builder()
                                .strategyName(name()).selectedAgent(agents.get(0).getAgentName())
                                .confidence(0.0).reasoning("LLM 返回无法解析").build();
                    }

                    if (agents.stream().noneMatch(a -> a.getAgentName().equals(parsed.getSelectedAgent()))) {
                        log.warn("LLM 返回的 Agent 名称无效: {}, 回退到首个 Agent", parsed.getSelectedAgent());
                        return RoutingResult.builder()
                                .strategyName(name()).selectedAgent(agents.get(0).getAgentName())
                                .confidence(0.0).reasoning("LLM 返回无效名称: " + parsed.getSelectedAgent()).build();
                    }

                    return RoutingResult.builder()
                            .strategyName(name())
                            .selectedAgent(parsed.getSelectedAgent())
                            .confidence(parsed.getConfidence())
                            .reasoning(parsed.getReasoning())
                            .build();
                });
    }

    /**
     * 用 Jackson 解析 LLM 返回的 JSON，兼容 markdown 代码块包裹。
     */
    private RoutingResult parseRoutingResponse(String response) {
        if (response == null || response.isBlank()) return null;
        try {
            // 提取 JSON 对象部分（去除 markdown 代码块等外围内容）
            int start = response.indexOf('{');
            int end = response.lastIndexOf('}') + 1;
            if (start < 0 || end <= start) return null;

            JsonNode root = objectMapper.readTree(response.substring(start, end));
            String selectedAgent = root.has("selectedAgent") ? root.get("selectedAgent").asText() : null;
            double confidence = root.has("confidence") ? root.get("confidence").asDouble(0.0) : 0.0;
            String reasoning = root.has("reasoning") ? root.get("reasoning").asText() : null;

            return RoutingResult.builder()
                    .selectedAgent(selectedAgent)
                    .confidence(confidence)
                    .reasoning(reasoning)
                    .build();
        } catch (Exception e) {
            log.warn("解析 LLM 路由 JSON 失败: {}", e.getMessage());
            return null;
        }
    }
}
