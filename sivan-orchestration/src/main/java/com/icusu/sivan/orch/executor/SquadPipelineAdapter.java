package com.icusu.sivan.orch.executor;

import com.icusu.sivan.common.enums.SquadMode;
import com.icusu.sivan.domain.orchestration.PhaseNode;
import com.icusu.sivan.domain.shared.vo.TokenContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * PhaseNode → 原生 Pipeline 执行。
 * <p>
 * 将阶段内 Agent 执行映射为顺序链或并行扇出，
 * 原生 Pipeline 执行。
 */
@Slf4j
@Component
public class SquadPipelineAdapter {

    /**
     * 执行阶段内的 Agent 列表（SEQUENTIAL 或 PARALLEL）。
     */
    public Mono<PhaseResult> executePhase(PhaseNode phase, String input,
                                           UUID executionId, UUID accountId,
                                           int phaseIndex, PhaseCallbacks callbacks,
                                           List<AgentCheckpoint> resumeCheckpoints) {
        List<String> agents = phase.getAgents();
        if (agents == null || agents.isEmpty()) {
            return Mono.just(PhaseResult.success(input));
        }

        SquadMode mode = phase.getMode() != null ? phase.getMode() : SquadMode.SEQUENTIAL;
        return switch (mode) {
            case PARALLEL -> executeParallel(phase, input, executionId, accountId, phaseIndex, callbacks, resumeCheckpoints);
            default -> executeSequential(phase, input, executionId, accountId, phaseIndex, callbacks, resumeCheckpoints);
        };
    }

    /** 顺序执行：agent[i] 的输出作为 agent[i+1] 的输入。支持检查点续跑。 */
    private Mono<PhaseResult> executeSequential(PhaseNode phase, String input,
                                                 UUID executionId, UUID accountId,
                                                 int phaseIndex, PhaseCallbacks callbacks,
                                                 List<AgentCheckpoint> resumeCheckpoints) {
        List<String> agents = phase.getAgents();

        // 处理检查点恢复：跳过已完成 Agent，跟踪最后输出
        String initialInput = input;
        int startFrom = 0;
        List<AgentCheckpoint> initialCheckpoints = new ArrayList<>(resumeCheckpoints);
        if (!resumeCheckpoints.isEmpty()) {
            for (AgentCheckpoint cp : resumeCheckpoints) {
                if ("COMPLETED".equals(cp.status()) && cp.agentIndex() >= startFrom) {
                    startFrom = cp.agentIndex() + 1;
                    if (cp.output() != null) initialInput = cp.output();
                }
            }
            log.info("PIPELINE SEQUENTIAL 从检查点恢复: startFrom={}/{}", startFrom, agents.size());
        }

        List<String> remaining = agents.subList(startFrom, agents.size());
        if (remaining.isEmpty()) {
            return Mono.just(PhaseResult.success(initialInput, initialCheckpoints));
        }

        TokenContext ctx = callbacks.createTokenContext(accountId, executionId);
        int startFromFinal = startFrom;

        // 递归执行 Agent 链
        return executeAgentChain(remaining, initialInput, startFromFinal, agents.size(), phase,
                callbacks, executionId, accountId, phaseIndex, ctx, new ArrayList<>())
                .flatMap(result -> {
                    List<AgentCheckpoint> finalCps = new ArrayList<>(resumeCheckpoints);
                    for (FutureResult fr : result.results) {
                        finalCps.add(new AgentCheckpoint(fr.agentIndex(), fr.agentId(),
                                "COMPLETED", fr.content(), null));
                    }
                    callbacks.saveAgentCheckpoints(executionId, phaseIndex, finalCps);
                    return Mono.just(PhaseResult.success(result.output(), finalCps));
                });
    }

    /** 递归执行 Agent 链。 */
    private Mono<ChainResult> executeAgentChain(List<String> remaining, String input,
                                                  int startOffset, int totalAgents,
                                                  PhaseNode phase, PhaseCallbacks callbacks,
                                                  UUID executionId, UUID accountId, int phaseIndex,
                                                  TokenContext ctx, List<FutureResult> results) {
        if (remaining.isEmpty()) {
            return Mono.just(new ChainResult(input, results));
        }

        String agentId = remaining.get(0);
        int globalIdx = startOffset;
        String prompt = PhaseExecutor.buildPrompt(phase, input);

        return callbacks.callReactAgent(agentId, prompt, ctx)
                .flatMap(result -> {
                    callbacks.afterAgentLlm(agentId, result);
                    String nextAgent = globalIdx + 1 < totalAgents
                            ? phase.getAgents().get(globalIdx + 1) : null;
                    callbacks.saveContract(executionId, accountId, phaseIndex,
                            agentId, nextAgent, result.content());
                    results.add(new FutureResult(globalIdx, agentId, result.content()));
                    return executeAgentChain(remaining.subList(1, remaining.size()), result.content(),
                            startOffset + 1, totalAgents, phase, callbacks, executionId,
                            accountId, phaseIndex, ctx, results);
                });
    }

    /** 并行执行：所有 Agent 同时执行，结果按原始顺序合并。支持检查点续跑。 */
    private Mono<PhaseResult> executeParallel(PhaseNode phase, String input,
                                               UUID executionId, UUID accountId,
                                               int phaseIndex, PhaseCallbacks callbacks,
                                               List<AgentCheckpoint> resumeCheckpoints) {
        List<String> agents = phase.getAgents();

        var completedAgents = new java.util.HashSet<String>();
        var completedOutputs = new java.util.HashMap<String, String>();
        for (AgentCheckpoint cp : resumeCheckpoints) {
            if ("COMPLETED".equals(cp.status())) {
                completedAgents.add(cp.agentName());
                completedOutputs.put(cp.agentName(), cp.output());
            }
        }

        List<String> pendingAgents = agents.stream()
                .filter(a -> !completedAgents.contains(a))
                .toList();

        if (!completedAgents.isEmpty()) {
            log.info("PIPELINE PARALLEL 从检查点恢复: skip {} completed, run {} pending",
                    completedAgents.size(), pendingAgents.size());
        }

        TokenContext ctx = callbacks.createTokenContext(accountId, executionId);
        List<FutureResult> results = new ArrayList<>();

        Mono<List<FutureResult>> executionMono;
        if (!pendingAgents.isEmpty()) {
            List<Mono<FutureResult>> monos = pendingAgents.stream()
                    .map(agentId -> {
                        int agentIndex = agents.indexOf(agentId);
                        String prompt = PhaseExecutor.buildPrompt(phase, input);
                        return callbacks.callReactAgent(agentId, prompt, ctx)
                                .map(llmResult -> {
                                    callbacks.afterAgentLlm(agentId, llmResult);
                                    String nextAgent = agentIndex + 1 < agents.size()
                                            ? agents.get(agentIndex + 1) : null;
                                    callbacks.saveContract(executionId, accountId, phaseIndex,
                                            agentId, nextAgent, llmResult.content());
                                    return new FutureResult(agentIndex, agentId, llmResult.content());
                                });
                    })
                    .toList();
            executionMono = Mono.zip(monos, raw -> {
                for (Object r : raw) {
                    results.add((FutureResult) r);
                }
                return results;
            });
        } else {
            executionMono = Mono.just(results);
        }

        return executionMono.map(allResults -> {
            List<AgentCheckpoint> checkpoints = new ArrayList<>(resumeCheckpoints);
            for (FutureResult fr : allResults) {
                checkpoints.add(new AgentCheckpoint(fr.agentIndex(), fr.agentId(),
                        "COMPLETED", fr.content(), null));
            }
            callbacks.saveAgentCheckpoints(executionId, phaseIndex, checkpoints);

            // 合并输出（按原始顺序）
            StringBuilder merged = new StringBuilder();
            for (String agentId : agents) {
                String output = completedOutputs.get(agentId);
                if (output == null) {
                    for (FutureResult fr : allResults) {
                        if (fr.agentId().equals(agentId)) {
                            output = fr.content();
                            break;
                        }
                    }
                }
                if (output != null) {
                    if (!merged.isEmpty()) merged.append("\n\n");
                    merged.append(output);
                }
            }
            return PhaseResult.success(merged.toString().strip(), checkpoints);
        });
    }

    /** 中间结果记录（用于 Pipeline 执行后重建检查点）。 */
    private record FutureResult(int agentIndex, String agentId, String content) {}

    /** 递归链式执行结果。 */
    private record ChainResult(String output, List<FutureResult> results) {}
}
