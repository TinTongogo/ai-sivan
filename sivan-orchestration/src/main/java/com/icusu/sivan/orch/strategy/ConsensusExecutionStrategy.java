package com.icusu.sivan.orch.strategy;

import com.icusu.sivan.agent.prompt.OrchestrationPrompts;
import com.icusu.sivan.common.enums.SquadMode;
import com.icusu.sivan.domain.orchestration.PhaseNode;
import com.icusu.sivan.domain.shared.vo.TokenContext;
import com.icusu.sivan.orch.executor.AgentCheckpoint;
import com.icusu.sivan.orch.executor.JsonSubTaskParser;
import com.icusu.sivan.orch.executor.PhaseCallbacks;
import com.icusu.sivan.orch.executor.PhaseResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.stream.Collectors;

/**
 * CONSENSUS 阶段内执行：二轮共识 + HITL 兜底。
 *
 * <p>第一轮: 所有 Agent 独立执行 → LLM 综合（含置信度评分）
 * <br>├─ 置信度 ≥ 0.7 → 返回结果
 * <br>└─ 置信度 < 0.7 → 第二轮（仅分歧 Agent，注入上下文）
 * <br>&nbsp;&nbsp;&nbsp;&nbsp;├─ 置信度 ≥ 0.7 → 返回结果
 * <br>&nbsp;&nbsp;&nbsp;&nbsp;└─ 置信度 < 0.7 → PhaseResult.paused()，HITL 决策
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConsensusExecutionStrategy implements PhaseExecutionStrategy {

    @Override
    public SquadMode supportedMode() {
        return SquadMode.CONSENSUS;
    }

    @Override
    public Mono<PhaseResult> execute(PhaseNode phase, String input, UUID executionId,
                                      UUID accountId, int phaseIndex, PhaseCallbacks callbacks,
                                      List<AgentCheckpoint> resumeCheckpoints) {
        List<String> agents = phase.getAgents();
        if (agents == null || agents.isEmpty()) return Mono.just(PhaseResult.success(input));

        // 单智能体无需共识合成
        if (agents.size() == 1) {
            TokenContext ctx = callbacks.createTokenContext(accountId, executionId);
            return callbacks.callReactAgent(agents.get(0), buildPhasePrompt(phase, input), ctx)
                    .map(answer -> {
                        callbacks.afterAgentLlm(agents.get(0), answer);
                        return PhaseResult.success(answer.content());
                    });
        }

        Set<String> completedAgents = resumeCheckpoints.stream()
                .filter(c -> "COMPLETED".equals(c.status()))
                .map(AgentCheckpoint::agentName)
                .collect(Collectors.toSet());

        TokenContext ctx = callbacks.createTokenContext(accountId, executionId);

        // 1. 第一轮：只跑未完成的 Agent
        return executeFirstRound(agents, completedAgents, resumeCheckpoints, phase, input, ctx, callbacks)
                .flatMap(roundResult -> {
                    List<AgentCheckpoint> checkpoints = roundResult.checkpoints();
                    Map<String, PhaseCallbacks.LlmResult> results = roundResult.results();

                    // 2. 第一轮综合
                    return synthesizeWithConfidence(agents, results, callbacks, ctx)
                            .flatMap(firstRound -> {
                                if (firstRound.confidence() >= 0.7) {
                                    log.info("CONSENSUS 第一轮达成共识: confidence={}",
                                            String.format("%.2f", firstRound.confidence()));
                                    buildCheckpoints(agents, results, checkpoints, firstRound.dissenters());
                                    callbacks.saveAgentCheckpoints(executionId, phaseIndex, checkpoints);
                                    return Mono.just(PhaseResult.success(
                                            "【共识结果 信度=" + String.format("%.2f", firstRound.confidence()) + "】\n"
                                                    + firstRound.conclusion(), checkpoints));
                                }

                                if (callbacks.isCancelled(executionId)) {
                                    return Mono.just(PhaseResult.paused("CONSENSUS: 执行已取消"));
                                }

                                // 3. 低置信度 → 第二轮
                                List<String> dissenters = firstRound.dissenters();
                                log.info("CONSENSUS 置信度不足 ({}), 分歧方={}, 启动第二轮",
                                        String.format("%.2f", firstRound.confidence()), dissenters);

                                buildCheckpoints(agents, results, checkpoints, dissenters);

                                return executeSecondRound(dissenters, firstRound, results, phase, ctx, callbacks)
                                        .flatMap(secondRound -> {
                                            if (secondRound.confidence() >= 0.7) {
                                                log.info("CONSENSUS 第二轮达成共识: confidence={}",
                                                        String.format("%.2f", secondRound.confidence()));
                                                return Mono.just(PhaseResult.success(
                                                        "【共识结果 信度=" + String.format("%.2f", secondRound.confidence()) + "】\n"
                                                                + secondRound.conclusion(), checkpoints));
                                            }

                                            // 4. 仍无共识 → HITL 暂停
                                            log.warn("CONSENSUS 二轮仍未达成共识: confidence={}，进入 HITL",
                                                    String.format("%.2f", secondRound.confidence()));
                                            callbacks.publishEvent(executionId, "HITL_PENDING", phaseIndex,
                                                    phase.getName(), "CONSENSUS 未达成共识，需人工决策");
                                            String partialResult = "多数意见："
                                                    + (secondRound.majorityOpinion() != null ? secondRound.majorityOpinion() : firstRound.majorityOpinion())
                                                    + "\n分歧点：" + String.join("; ",
                                                            secondRound.dissentPoints().isEmpty() ? firstRound.dissentPoints() : secondRound.dissentPoints())
                                                    + "\n建议综合：\n" + secondRound.conclusion();
                                            return Mono.just(PhaseResult.paused(
                                                    "CONSENSUS 未达成共识: " + partialResult, partialResult, checkpoints));
                                        });
                            });
                });
    }

    /** 第一轮：恢复检查点 + 执行未完成的 Agent。 */
    private Mono<FirstRoundResult> executeFirstRound(
            List<String> agents, Set<String> completedAgents,
            List<AgentCheckpoint> resumeCheckpoints, PhaseNode phase, String input,
            TokenContext ctx, PhaseCallbacks callbacks) {
        Map<String, PhaseCallbacks.LlmResult> results = new LinkedHashMap<>();
        List<AgentCheckpoint> cps = new ArrayList<>(resumeCheckpoints);

        for (AgentCheckpoint cp : resumeCheckpoints) {
            if ("COMPLETED".equals(cp.status()) && cp.output() != null) {
                results.put(cp.agentName(), new PhaseCallbacks.LlmResult(cp.output(), null));
            }
        }

        // 只执行未完成的 Agent
        return Flux.fromIterable(agents)
                .filter(agentId -> !completedAgents.contains(agentId))
                .concatMap(agentId ->
                        callbacks.callReactAgent(agentId, buildPhasePrompt(phase, input), ctx)
                                .map(answer -> {
                                    callbacks.afterAgentLlm(agentId, answer);
                                    results.put(agentId, answer);
                                    return answer;
                                }))
                .then(Mono.just(new FirstRoundResult(results, cps)));
    }

    /** 第二轮：仅分歧方重新执行。 */
    private Mono<JsonSubTaskParser.SynthesisResult> executeSecondRound(
            List<String> dissenters,
            JsonSubTaskParser.SynthesisResult firstRound,
            Map<String, PhaseCallbacks.LlmResult> results,
            PhaseNode phase, TokenContext ctx, PhaseCallbacks callbacks) {
        return Flux.fromIterable(dissenters)
                .concatMap(dissenterId -> {
                    String secondRoundPrompt = buildSecondRoundPrompt(phase.getDescription(),
                            firstRound.majorityOpinion(), firstRound.dissentPoints(),
                            results.get(dissenterId));
                    return callbacks.callReactAgent(dissenterId, secondRoundPrompt, ctx)
                            .map(redo -> {
                                callbacks.afterAgentLlm(dissenterId + "(第二轮)", redo);
                                results.put(dissenterId + "(第二轮)", redo);
                                return redo;
                            });
                })
                .then(Mono.defer(() -> synthesizeWithConfidence(
                        new ArrayList<>(results.keySet()).stream()
                                .filter(k -> !k.endsWith("(第二轮)") || results.containsKey(k))
                                .collect(Collectors.toList()),
                        results, callbacks, ctx)));
    }

    /** 构建检查点。 */
    private void buildCheckpoints(List<String> agents,
                                   Map<String, PhaseCallbacks.LlmResult> results,
                                   List<AgentCheckpoint> checkpoints,
                                   List<String> dissenters) {
        for (String agentId : agents) {
            if (checkpoints.stream().noneMatch(cp -> cp.agentName().equals(agentId))) {
                int idx = agents.indexOf(agentId);
                String mode = dissenters.contains(agentId) ? "DISSENT" : "AGREE";
                PhaseCallbacks.LlmResult r = results.get(agentId);
                checkpoints.add(new AgentCheckpoint(idx, agentId, "COMPLETED",
                        r != null ? r.content() : null, mode));
            }
        }
    }

    /** 结构化综合：要求 LLM 以固定格式输出多数意见、分歧点、置信度。 */
    private Mono<JsonSubTaskParser.SynthesisResult> synthesizeWithConfidence(
            List<String> agents,
            Map<String, PhaseCallbacks.LlmResult> results,
            PhaseCallbacks callbacks, TokenContext ctx) {

        LinkedHashMap<String, String> agentResults = new LinkedHashMap<>();
        for (String agentId : agents) {
            PhaseCallbacks.LlmResult r = results.get(agentId);
            if (r != null) {
                agentResults.put(agentId, r.content());
            }
        }
        String prompt = OrchestrationPrompts.ORCHESTRATION_SYSTEM.content() + "\n\n"
                + OrchestrationPrompts.consensusSynthesisUser(agentResults).content();

        return callbacks.callLlm(prompt, ctx)
                .map(synthesis -> {
                    callbacks.afterAgentLlm("综合", synthesis);
                    return JsonSubTaskParser.parseSynthesisResult(synthesis.content());
                });
    }

    /** 第一轮执行结果容器。 */
    private record FirstRoundResult(Map<String, PhaseCallbacks.LlmResult> results,
                                     List<AgentCheckpoint> checkpoints) {}

    /** 构建第二轮 prompt。 */
    private static String buildSecondRoundPrompt(String taskDescription,
                                                  String majorityOpinion, List<String> dissentPoints,
                                                  PhaseCallbacks.LlmResult previousAnswer) {
        return OrchestrationPrompts.consensusSecondRoundUser(
                taskDescription != null ? taskDescription : "",
                majorityOpinion,
                dissentPoints.isEmpty() ? "（无明确分歧）" : String.join("\n", dissentPoints),
                previousAnswer.content()).content();
    }

    /** 构建阶段内 LLM 调用提示词。 */
    private static String buildPhasePrompt(PhaseNode phase, String input) {
        return OrchestrationPrompts.phaseTaskUser(input, phase.getDescription()).content();
    }
}
