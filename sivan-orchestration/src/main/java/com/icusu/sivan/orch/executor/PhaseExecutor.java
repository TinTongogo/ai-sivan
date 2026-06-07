package com.icusu.sivan.orch.executor;

import com.icusu.sivan.orch.strategy.PhaseExecutionStrategy;
import com.icusu.sivan.agent.prompt.OrchestrationPrompts;
import com.icusu.sivan.common.enums.SquadMode;
import com.icusu.sivan.domain.orchestration.PhaseNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 阶段内执行器。根据 phase.mode 委派给对应的 PhaseExecutionStrategy 实现。
 * <p>
 * 负责 HITL 三节点嵌入（PRE / MID / POST）、异常处理等横切关注点。
 */
@Slf4j
@Component
public class PhaseExecutor {

    private final Map<SquadMode, PhaseExecutionStrategy> executionStrategies;

    public PhaseExecutor(List<PhaseExecutionStrategy> strategyBeans) {
        this.executionStrategies = strategyBeans.stream()
                .collect(Collectors.toMap(PhaseExecutionStrategy::supportedMode, Function.identity()));
    }

    /** HITL 模式常量。 */
    static final String HITL_NONE = "NONE";
    static final String HITL_PRE = "PRE";
    static final String HITL_POST = "POST";
    static final String HITL_ALL = "ALL";
    static final String HITL_AGENT_LIST = "AGENT_LIST";

    /**
     * 根据 phase.mode 分派到对应的阶段内执行策略。
     * 按 hitlMode 嵌入 HITL 节点：
     * <ul>
     *   <li>PRE — 执行前暂停，人工审核输入</li>
     *   <li>POST — 执行完成后暂停</li>
     *   <li>AGENT_LIST — 指定 Agent 完成后暂停</li>
     *   <li>NONE/null — 不暂停</li>
     * </ul>
     *
     * @param phase       当前阶段
     * @param input       阶段输入（可能已被 restartHint 或 hitlOverride 改写）
     * @param executionId 执行 ID
     * @param accountId   账户 ID
     * @param phaseIndex  阶段索引
     * @param callbacks   回调
     * @param restartHint RESTART_PHASE 时注入的重启提示，null 表示正常执行
     */
    public Mono<PhaseResult> dispatchPhase(PhaseNode phase, String input, UUID executionId,
                                            UUID accountId, int phaseIndex, PhaseCallbacks callbacks,
                                            String restartHint) {
        try {
            String effectiveInput = input;
            if (restartHint != null && !restartHint.isBlank()) {
                effectiveInput = injectRestartHint(input, restartHint);
                log.info("RESTART_PHASE 注入重启提示: phase={}, hint={}", phaseIndex, restartHint);
            }

            String hitlMode = resolveHitlMode(phase);
            List<AgentCheckpoint> resumeCps = callbacks.loadAgentCheckpoints(executionId, phaseIndex);

            // ===== PRE 节点：执行前人工审核 =====
            if (HITL_PRE.equals(hitlMode) || HITL_ALL.equals(hitlMode)) {
                callbacks.publishEvent(executionId, "HITL_PENDING", phaseIndex, phase.getName(),
                        "PRE 审核: 阶段执行前需人工确认输入");
                return Mono.just(PhaseResult.paused(effectiveInput,
                        "PRE 审核: " + (phase.getName() != null ? phase.getName() : ""),
                        resumeCps));
            }

            SquadMode squadMode = phase.getMode() != null ? phase.getMode() : SquadMode.SEQUENTIAL;
            List<String> agents = phase.getAgents();
            if (agents == null || agents.isEmpty()) {
                return Mono.just(PhaseResult.success(effectiveInput));
            }

            PhaseExecutionStrategy strategy = executionStrategies.get(squadMode);
            if (strategy == null) {
                log.warn("不支持的阶段内模式: {}，回退到 SEQUENTIAL", squadMode);
                strategy = executionStrategies.get(SquadMode.SEQUENTIAL);
            }
            return strategy.execute(phase, effectiveInput, executionId,
                    accountId, phaseIndex, callbacks, resumeCps)
                    .flatMap(result -> {
                        // ===== MID / AGENT_LIST 节点：指定 Agent 完成后暂停 =====
                        if (HITL_AGENT_LIST.equals(hitlMode) && !result.paused()) {
                            if (hasHitlAgentInCheckpoints(phase, result.checkpoints())) {
                                callbacks.publishEvent(executionId, "HITL_PENDING", phaseIndex, phase.getName(),
                                        "AGENT_LIST 审核: 指定 Agent 完成，需人工审核");
                                return Mono.just(PhaseResult.paused(result.content(),
                                        "Agent 审核: " + (phase.getName() != null ? phase.getName() : ""),
                                        result.checkpoints()));
                            }
                        }

                        // ===== POST 节点：阶段执行完成后人工审核 =====
                        if (isPostHitl(hitlMode) && !result.paused()) {
                            callbacks.publishEvent(executionId, "HITL_PENDING", phaseIndex, phase.getName(),
                                    "阶段完成，需人工审核");
                            return Mono.just(PhaseResult.paused(result.content(),
                                    "阶段审核: " + (phase.getName() != null ? phase.getName() : ""),
                                    result.checkpoints()));
                        }
                        return Mono.just(result);
                    });
        } catch (Exception e) {
            log.error("阶段 [{}] 执行异常，转为暂停等待人工处理", phase.getName(), e);
            String phaseName = phase.getName() != null ? phase.getName() : "阶段" + phaseIndex;
            callbacks.publishEvent(executionId, "HITL_PENDING", phaseIndex, phaseName,
                    "执行异常: " + (e.getMessage() != null ? e.getMessage() : "未知错误"));
            return Mono.just(PhaseResult.paused("执行异常 [阶段" + phaseIndex + " " + phaseName + "]: "
                    + (e.getMessage() != null ? e.getMessage() : "未知错误")));
        }
    }

    /** 构建 LLM 调用提示词。 */
    public static String buildPrompt(PhaseNode phase, String input) {
        return OrchestrationPrompts.phaseTaskUser(input, phase.getDescription()).content();
    }

    /**
     * 解析有效的 hitlMode。null 或未知值视为 NONE。
     */
    static String resolveHitlMode(PhaseNode phase) {
        String hitlMode = phase.getHitlMode();
        if (hitlMode != null) {
            return switch (hitlMode) {
                case HITL_PRE, HITL_POST, HITL_ALL, HITL_AGENT_LIST -> hitlMode;
                default -> HITL_NONE;
            };
        }
        return HITL_NONE;
    }

    /**
     * 判断是否为 POST 模式审核。
     */
    private static boolean isPostHitl(String hitlMode) {
        return HITL_POST.equals(hitlMode) || HITL_ALL.equals(hitlMode);
    }

    /**
     * 检查 checkpoints 中是否包含 hitlAgents 列表中的 Agent。
     * 只要有一个匹配即返回 true（该 Agent 刚完成执行）。
     */
    private static boolean hasHitlAgentInCheckpoints(PhaseNode phase, List<AgentCheckpoint> checkpoints) {
        List<String> hitlAgents = phase.getHitlAgents();
        if (hitlAgents == null || hitlAgents.isEmpty() || checkpoints == null) {
            return false;
        }
        Set<String> agentSet = checkpoints.stream()
                .map(AgentCheckpoint::agentName)
                .collect(Collectors.toSet());
        return hitlAgents.stream().anyMatch(agentSet::contains);
    }

    /**
     * RESTART_PHASE 时将重启提示注入到阶段输入中。
     */
    private static String injectRestartHint(String input, String restartHint) {
        return """
                ⚠️ [RESTART_PHASE — 人工修正提示]
                请根据以下反馈重新执行本阶段：

                %s

                ——— 原始阶段输入 ———
                %s
                """.formatted(restartHint, input != null ? input : "");
    }
}
