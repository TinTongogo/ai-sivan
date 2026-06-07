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
 * HIERARCHICAL 阶段内执行：管理者分解任务为结构化 JSON 子任务 → 按依赖 DAG 拓扑序调度。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HierarchicalExecutionStrategy implements PhaseExecutionStrategy {

    @Override
    public SquadMode supportedMode() {
        return SquadMode.HIERARCHICAL;
    }

    @Override
    public Mono<PhaseResult> execute(PhaseNode phase, String input, UUID executionId,
                                      UUID accountId, int phaseIndex, PhaseCallbacks callbacks,
                                      List<AgentCheckpoint> resumeCheckpoints) {
        List<String> agents = phase.getAgents();
        if (agents == null || agents.isEmpty()) return Mono.just(PhaseResult.success(input));

        TokenContext ctx = callbacks.createTokenContext(accountId, executionId);

        // 从检查点还原已完成的子任务
        Set<Integer> completedSubtaskIds = resumeCheckpoints.stream()
                .filter(c -> "COMPLETED".equals(c.status()) && c.mode() != null)
                .map(c -> { try { return Integer.parseInt(c.mode()); } catch (NumberFormatException e) { return -1; } })
                .filter(id -> id >= 0)
                .collect(Collectors.toSet());
        Map<Integer, String> completedOutputs = resumeCheckpoints.stream()
                .filter(c -> "COMPLETED".equals(c.status()) && c.mode() != null)
                .filter(c -> { try { Integer.parseInt(c.mode()); return true; } catch (NumberFormatException e) { return false; } })
                .collect(Collectors.toMap(c -> Integer.parseInt(c.mode()), AgentCheckpoint::output));

        // 1. 管理者分解任务
        String managerId = agents.getFirst();
        String decomposePrompt = OrchestrationPrompts.hierarchicalDecomposeUser(input).content();
        return callbacks.callReactAgent(managerId, decomposePrompt, ctx)
                .flatMap(decomposition -> {
                    callbacks.afterAgentLlm(managerId + "(管理者)", decomposition);

                    // 2. 解析子任务
                    List<JsonSubTaskParser.SubTask> subtasks = JsonSubTaskParser.parseSubTasks(decomposition.content());
                    if (subtasks.isEmpty()) {
                        log.warn("HIERARCHICAL 解析子任务失败，回退到顺序执行");
                        return fallbackExecute(agents, decomposition.content(), input, executionId,
                                accountId, phaseIndex, callbacks, ctx);
                    }
                    log.info("HIERARCHICAL 解析到 {} 个子任务", subtasks.size());

                    // 3. DAG 调度
                    Map<Integer, String> subOutputs = new LinkedHashMap<>(completedOutputs);
                    Map<Integer, JsonSubTaskParser.SubTask> taskMap = new LinkedHashMap<>();
                    Map<Integer, Integer> inDegree = new LinkedHashMap<>();

                    for (JsonSubTaskParser.SubTask t : subtasks) {
                        taskMap.put(t.id(), t);
                        int deps = t.dependsOn() != null
                                ? (int) t.dependsOn().stream().filter(d -> !completedSubtaskIds.contains(d)).count()
                                : 0;
                        inDegree.put(t.id(), deps);
                    }

                    Queue<Integer> ready = new LinkedList<>();
                    for (Map.Entry<Integer, Integer> e : inDegree.entrySet()) {
                        if (e.getValue() == 0 && !completedSubtaskIds.contains(e.getKey())) ready.add(e.getKey());
                    }

                    List<AgentCheckpoint> checkpoints = new ArrayList<>(resumeCheckpoints);
                    String phaseDesc = phase.getDescription() != null ? phase.getDescription() : "";

                    return executeDag(taskMap, inDegree, subOutputs, ready, completedSubtaskIds,
                            phaseDesc, executionId, callbacks, ctx, checkpoints)
                            .map(finalOutputs -> {
                                // 4. 组装结果
                                StringBuilder result = new StringBuilder("【HIERARCHICAL 执行结果】\n");
                                for (Map.Entry<Integer, String> e : finalOutputs.entrySet()) {
                                    JsonSubTaskParser.SubTask task = taskMap.get(e.getKey());
                                    result.append("\n--- 子任务 ").append(e.getKey()).append(": ")
                                            .append(task != null ? task.goal() : "").append(" ---\n")
                                            .append(e.getValue()).append("\n");
                                }
                                callbacks.saveAgentCheckpoints(executionId, phaseIndex, checkpoints);
                                return PhaseResult.success(result.toString().strip(), checkpoints);
                            });
                });
    }

    /** 递归 DAG 调度：每批次并行执行就绪任务。 */
    private Mono<Map<Integer, String>> executeDag(
            Map<Integer, JsonSubTaskParser.SubTask> taskMap,
            Map<Integer, Integer> inDegree,
            Map<Integer, String> subOutputs,
            Collection<Integer> ready,
            Set<Integer> completedIds,
            String phaseDesc,
            UUID executionId,
            PhaseCallbacks callbacks,
            TokenContext ctx,
            List<AgentCheckpoint> checkpoints) {
        if (ready.isEmpty()) {
            return Mono.just(subOutputs);
        }

        if (callbacks.isCancelled(executionId)) {
            return Mono.error(new RuntimeException("HIERARCHICAL: 执行已取消"));
        }

        return Flux.fromIterable(ready)
                .flatMap(taskId -> {
                    JsonSubTaskParser.SubTask task = taskMap.get(taskId);
                    String context = buildSubTaskInput(task, subOutputs, taskMap);
                    String prompt = phaseDesc.isBlank() ? context : phaseDesc + "\n\n" + context;
                    return callbacks.callLlm(prompt, ctx)
                            .map(result -> {
                                callbacks.afterAgentLlm("子任务" + taskId, result);
                                checkpoints.add(new AgentCheckpoint(taskId, "subtask-" + taskId,
                                        "COMPLETED", result.content(), String.valueOf(taskId)));
                                return new AbstractMap.SimpleEntry<>(taskId, result.content());
                            });
                })
                .collectList()
                .flatMap(entries -> {
                    for (var entry : entries) {
                        subOutputs.put(entry.getKey(), entry.getValue());
                        completedIds.add(entry.getKey());
                        callbacks.saveAgentCheckpoints(executionId, -1, checkpoints);
                    }

                    // 更新入度，找出下一批就绪任务
                    List<Integer> nextBatch = new ArrayList<>();
                    for (var e : inDegree.entrySet()) {
                        int tid = e.getKey();
                        if (completedIds.contains(tid) || e.getValue() <= 0) continue;
                        JsonSubTaskParser.SubTask t = taskMap.get(tid);
                        long remainingDeps = t.dependsOn().stream()
                                .filter(d -> !completedIds.contains(d)).count();
                        e.setValue((int) remainingDeps);
                        if (remainingDeps == 0) nextBatch.add(tid);
                    }
                    return executeDag(taskMap, inDegree, subOutputs, nextBatch, completedIds,
                            phaseDesc, executionId, callbacks, ctx, checkpoints);
                });
    }

    /** JSON 解析失败时的降级方案。 */
    private Mono<PhaseResult> fallbackExecute(List<String> agents, String decompositionText, String input,
                                               UUID executionId, UUID accountId, int phaseIndex,
                                               PhaseCallbacks callbacks, TokenContext ctx) {
        return Flux.fromIterable(agents.subList(1, agents.size()))
                .concatMap(agent -> {
                    String subPrompt = "管理者的任务分解：\n" + decompositionText + "\n\n你负责的部分：\n" + input;
                    return callbacks.callReactAgent(agent, subPrompt, ctx)
                            .map(result -> {
                                callbacks.afterAgentLlm(agent, result);
                                return new AbstractMap.SimpleEntry<>(agent, result.content());
                            });
                })
                .collectList()
                .map(results -> {
                    StringBuilder result = new StringBuilder("【管理者分解】\n").append(decompositionText).append("\n\n");
                    for (var entry : results) {
                        result.append("【").append(entry.getKey()).append("执行】\n").append(entry.getValue()).append("\n\n");
                    }
                    return PhaseResult.success(result.toString().strip());
                });
    }

    /** 构建子任务输入。 */
    private static String buildSubTaskInput(JsonSubTaskParser.SubTask task,
                                             Map<Integer, String> previousOutputs,
                                             Map<Integer, JsonSubTaskParser.SubTask> taskMap) {
        String depSummary = "";
        if (task.dependsOn() != null && !task.dependsOn().isEmpty()) {
            StringBuilder ds = new StringBuilder("## 依赖产出的前置子任务结果\n");
            for (int depId : task.dependsOn()) {
                JsonSubTaskParser.SubTask depTask = taskMap.get(depId);
                if (depTask != null)
                    ds.append("--- 前置子任务 ").append(depId).append(": ").append(depTask.goal()).append(" ---\n");
                String depOutput = previousOutputs.get(depId);
                if (depOutput != null) ds.append(depOutput).append("\n\n");
            }
            depSummary = ds.toString();
        }
        return OrchestrationPrompts.subTaskUser(task.goal(), task.input(), task.expectedOutput(), depSummary).content();
    }
}
