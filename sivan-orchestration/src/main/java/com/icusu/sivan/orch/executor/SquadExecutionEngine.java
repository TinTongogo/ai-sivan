package com.icusu.sivan.orch.executor;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.icusu.sivan.orch.OrchestrationEngine;
import com.icusu.sivan.orch.contract.ContractFactory;
import com.icusu.sivan.agent.mcp.McpConnectionManager;
import com.icusu.sivan.agent.strategy.ReActExecutionStrategy;
import com.icusu.sivan.agent.tool.MatchedTools;
import com.icusu.sivan.agent.tool.ToolEnricher;
import com.icusu.sivan.agent.tool.ToolResolver;
import com.icusu.sivan.common.enums.ExecutionStatus;
import com.icusu.sivan.common.enums.SquadMode;
import com.icusu.sivan.common.enums.TokenSource;
import com.icusu.sivan.core.agent.Agent;
import com.icusu.sivan.core.agent.AgentEvent;
import com.icusu.sivan.core.agent.ExecutionStrategy;
import com.icusu.sivan.core.context.ExecutionContext;
import com.icusu.sivan.core.message.Msg;
import com.icusu.sivan.core.message.Role;
import com.icusu.sivan.agent.model.ModelRouter;
import com.icusu.sivan.core.model.Model.ModelParams;
import com.icusu.sivan.core.tool.ToolProvider;
import com.icusu.sivan.core.tool.ToolSpec;
import com.icusu.sivan.domain.agent.AgentDefinition;
import com.icusu.sivan.domain.orchestration.HitlReview;
import com.icusu.sivan.domain.orchestration.PhaseNode;
import com.icusu.sivan.domain.orchestration.Squad;
import com.icusu.sivan.domain.orchestration.SquadExecution;
import com.icusu.sivan.domain.agent.IAgentRepository;
import com.icusu.sivan.domain.orchestration.IContractRepository;
import com.icusu.sivan.domain.orchestration.IHitlReviewRepository;
import com.icusu.sivan.domain.orchestration.ISquadExecutionRepository;
import com.icusu.sivan.domain.orchestration.ISquadRepository;
import com.icusu.sivan.domain.orchestration.IExecutionArtifactRepository;
import com.icusu.sivan.domain.orchestration.ExecutionArtifact;
import com.icusu.sivan.domain.orchestration.ArtifactType;
import com.icusu.sivan.domain.orchestration.ContextPackage;
import com.icusu.sivan.domain.orchestration.PhaseOutput;
import com.icusu.sivan.domain.shared.vo.TokenContext;
import com.icusu.sivan.domain.orchestration.Contract;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Squad 异步执行引擎。支持 5 种编排模式：SEQUENTIAL / PARALLEL / CONDITIONAL / HIERARCHICAL / CONSENSUS。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SquadExecutionEngine implements OrchestrationEngine {

    @Override
    public String engineType() { return OrchestrationEngine.ASYNC; }

    private final PhaseScheduler phaseScheduler;
    private final ModelRouter modelRouter;
    private final ToolProvider toolProvider;
    private final ExecutionStrategy executionStrategy;
    private final ToolResolver toolResolver;
    private final ISquadExecutionRepository squadExecutionRepository;
    private final ISquadRepository squadRepository;
    private final IContractRepository contractRepository;
    private final IHitlReviewRepository hitlReviewRepository;
    private final IAgentRepository agentRepository;
    private final com.icusu.sivan.domain.agent.ISkillRepository skillRepository;
    private final McpConnectionManager mcpConnectionManager;
    private final ToolEnricher toolEnricher;
    private final IExecutionArtifactRepository executionArtifactRepository;

    /** 自注入代理，确保 @Async 在自调用时生效。 */
    @Autowired @Lazy
    private SquadExecutionEngine self;
    /**
     * 单个 Squad 执行超时（30 分钟）。
     */
    private static final long EXECUTION_TIMEOUT_MS = 30 * 60 * 1000L;

    /**
     * 执行取消标记。
     */
    private final ConcurrentHashMap<UUID, Boolean> cancelledExecutions = new ConcurrentHashMap<>();

    private final Sinks.Many<SquadExecutionEvent> eventSink =
            Sinks.many().multicast().onBackpressureBuffer(256);

    /**
     * 订阅指定 executionId 的进度事件。
     */
    public Flux<SquadExecutionEvent> events(UUID executionId) {
        return eventSink.asFlux().filter(e -> e.getExecutionId().equals(executionId));
    }

    /** 所有执行事件流（仪表盘等全局视图使用）。 */
    public Flux<SquadExecutionEvent> allExecutionEvents() {
        return eventSink.asFlux();
    }

    /**
     * 取消正在执行的 Squad。
     */
    public void cancel(UUID executionId) {
        cancelledExecutions.put(executionId, Boolean.TRUE);
        squadExecutionRepository.updateStatus(executionId, "CANCELLING", null);
        publishEvent(executionId, "CANCELLING", null, null, "取消请求已提交");
    }

    /**
     * 异步执行 Squad，不阻塞调用线程。
     * 委托 PhaseScheduler.executeSquad() 处理阶段间编排，回调处理 HITL/取消/超时/事件。
     */
    @Override
    @Async("squadTaskExecutor")
    public void execute(Squad squad, SquadExecution execution, UUID accountId) {
        UUID execId = execution.getExecutionId();
            cancelledExecutions.remove(execId);
            long startTime = System.currentTimeMillis();
            try {
                squadExecutionRepository.updateStatus(execId, "RUNNING", null);
                publishEvent(execId, "RUNNING", null, null, "开始执行");

                List<PhaseNode> phases = squad.getPhases();
                if (phases == null || phases.isEmpty()) {
                    log.warn("Squad 无阶段配置: squadId={}", squad.getSquadId());
                    squadExecutionRepository.updateStatus(execId, "COMPLETED", null);
                    publishEvent(execId, "COMPLETED", null, null, "Squad 无阶段配置");
                    return;
                }
                String previousOutput = execution.getTaskDescription();
                int startPhase = execution.getCurrentPhase() != null ? execution.getCurrentPhase() : 0;
                SquadMode squadMode = squad.getMode() != null ? squad.getMode() : SquadMode.SEQUENTIAL;

                PhaseCallbacks callbacks = createPhaseCallbacks(startTime, startPhase, accountId, execId,
                        execution.getProjectId());
                try {
                    PhaseResult result = phaseScheduler.executeSquad(phases, squadMode, previousOutput,
                            execId, accountId, callbacks, startPhase)
                            .doFinally(signal -> cancelledExecutions.remove(execId))
                            .block(Duration.ofMinutes(30));

                    if (result != null) {
                        if (result.paused()) {
                            String reason = result.pauseReason();
                            if ("CANCELLED".equals(reason) || "TIMEOUT".equals(reason)) {
                                log.info("Squad 执行终止: executionId={}, reason={}", execId, reason);
                            } else {
                                log.info("Squad 执行暂停: executionId={}, reason={}", execId, reason);
                            }
                        } else {
                            squadExecutionRepository.updateStatus(execId, "COMPLETED", null);
                            squadExecutionRepository.updateResult(execId, result.content(), null);
                            publishEvent(execId, "COMPLETED", phases.size() - 1,
                                    null, "Squad 执行完成");
                            squad.recordUsage();
                            squadRepository.update(squad);
                        }
                    }
                } catch (Exception e) {
                    log.error("Squad 执行失败: executionId={}", execId, e);
                    squadExecutionRepository.updateStatus(execId, "FAILED", e.getMessage());
                    publishEvent(execId, "FAILED", null, null,
                            "执行失败: " + e.getMessage());
                }

            } catch (Exception e) {
                cancelledExecutions.remove(execId);
                log.error("Squad 执行设置失败: squadId={}", squad.getSquadId(), e);
                squadExecutionRepository.updateStatus(execution.getExecutionId(), "FAILED", e.getMessage());
                publishEvent(execution.getExecutionId(), "FAILED", null, null,
                        "执行失败: " + e.getMessage());
            }
    }

    /**
     * 重试当前失败阶段。从 currentPhase 开始重新执行，保留前一阶段的输出作为输入。
     * 适用于阶段内 LLM 调用失败、工具异常等可重试场景。
     */
    @Async("squadTaskExecutor")
    public void retryPhase(SquadExecution execution, UUID accountId) {
        UUID execId = execution.getExecutionId();
        long startTime = System.currentTimeMillis();

        var currentOpt = squadExecutionRepository.findById(execId);
        if (currentOpt.isEmpty()) {
            log.warn("执行记录不存在: executionId={}", execId);
            return;
        }
        ExecutionStatus currentStatus = currentOpt.get().getStatus();
        if (currentStatus != ExecutionStatus.FAILED) {
            log.warn("仅 FAILED 状态可重试: executionId={}, status={}", execId, currentStatus);
            return;
        }

        try {
                squadExecutionRepository.updateStatus(execId, "RUNNING", null);
                int startPhase = execution.getCurrentPhase() != null ? execution.getCurrentPhase() : 0;
                publishEvent(execId, "RUNNING", startPhase, null,
                        "重试阶段 " + startPhase);

                Squad squad = squadRepository.findById(execution.getSquadId())
                        .orElseThrow(() -> new IllegalStateException("Squad not found: " + execution.getSquadId()));

                List<PhaseNode> phases = squad.getPhases();
                if (phases == null || phases.isEmpty()) {
                    log.warn("重试时 Squad 无阶段配置: squadId={}", execution.getSquadId());
                    squadExecutionRepository.updateStatus(execId, "COMPLETED", null);
                    return;
                }
                // 从前一阶段的 Contract 中获取输出作为重试输入（而非原始任务描述）
                String input = resolveInputFromPreviousPhase(execId, startPhase,
                        execution.getTaskDescription());
                SquadMode squadMode = squad.getMode() != null ? squad.getMode() : SquadMode.SEQUENTIAL;

                PhaseCallbacks callbacks = createPhaseCallbacks(startTime, startPhase, accountId, execId,
                        execution.getProjectId());
                try {
                    PhaseResult result = phaseScheduler.executeSquad(phases, squadMode, input,
                            execId, accountId, callbacks, startPhase)
                            .block(Duration.ofMinutes(30));

                    if (result != null) {
                        if (result.paused()) {
                            String reason = result.pauseReason();
                            if ("CANCELLED".equals(reason) || "TIMEOUT".equals(reason)) {
                                log.info("重试执行终止: executionId={}, reason={}", execId, reason);
                            } else {
                                log.info("重试执行暂停: executionId={}, reason={}", execId, reason);
                            }
                        } else {
                            squadExecutionRepository.updateStatus(execId, "COMPLETED", null);
                            squadExecutionRepository.updateResult(execId, result.content(), null);
                            publishEvent(execId, "COMPLETED", phases.size() - 1, null, "重试完成");
                            squad.recordUsage();
                            squadRepository.update(squad);
                        }
                    }
                } catch (Exception e) {
                    log.error("重试执行失败: executionId={}", execId, e);
                    squadExecutionRepository.updateStatus(execId, "FAILED",
                            "重试失败: " + e.getMessage());
                }
        } catch (Exception e) {
            log.error("重试执行失败: executionId={}", execution.getExecutionId(), e);
            squadExecutionRepository.updateStatus(execution.getExecutionId(), "FAILED",
                    "重试失败: " + e.getMessage());
        } finally {
            cancelledExecutions.remove(execId);
        }
    }

    /**
     * 继续执行被 HITL 暂定的 Squad。
     * <p>
     * 根据最新 HITL 审核记录的状态分支处理：
     * <ul>
     *   <li>CORRECTED — 使用修正内容作为输入（hitlOverride），从当前阶段重新执行</li>
     *   <li>RESTART_PHASE — 注入重启提示后从当前阶段重新执行</li>
     *   <li>RESTART_AGENT — 清除指定 Agent 的检查点后从当前阶段恢复</li>
     *   <li>APPROVED / TIMEOUT — 正常继续执行（现有行为）</li>
     * </ul>
     */
    @Async("squadTaskExecutor")
    public void resume(SquadExecution execution, UUID accountId) {
        UUID execId = execution.getExecutionId();
        long startTime = System.currentTimeMillis();

        // 状态前置检查：只有 HITL_PENDING 或 RUNNING 状态才允许恢复
        var currentOpt = squadExecutionRepository.findById(execId);
        if (currentOpt.isEmpty()) {
            log.warn("执行记录不存在: executionId={}", execId);
            return;
        }
        ExecutionStatus currentStatus = currentOpt.get().getStatus();
        if (currentStatus != ExecutionStatus.HITL_PENDING && currentStatus != ExecutionStatus.RUNNING) {
            log.warn("执行状态不允许恢复: executionId={}, status={}", execId, currentStatus);
            return;
        }

        try {
                squadExecutionRepository.updateStatus(execId, "RUNNING", null);
                int pausedPhase = execution.getCurrentPhase() != null ? execution.getCurrentPhase() : 0;
                publishEvent(execId, "RUNNING", pausedPhase, null, "继续执行");

                Squad squad = squadRepository.findById(execution.getSquadId())
                        .orElseThrow(() -> new IllegalStateException("Squad not found: " + execution.getSquadId()));

                List<PhaseNode> phases = squad.getPhases();
                if (phases == null || phases.isEmpty()) {
                    log.warn("恢复时 Squad 无阶段配置: squadId={}", execution.getSquadId());
                    squadExecutionRepository.updateStatus(execId, "COMPLETED", null);
                    return;
                }
                String previousOutput = execution.getTaskDescription();
                SquadMode squadMode = squad.getMode() != null ? squad.getMode() : SquadMode.SEQUENTIAL;

                // ===== 读取 HITL 审核记录，按状态分支处理 =====
                List<HitlReview> hitlReviews = hitlReviewRepository.findByExecutionIdAndPhase(execId, pausedPhase);
                String hitlOverride = null;
                String restartHint = null;
                String restartAgentName = null;
                boolean phaseResolved = false;

                for (HitlReview review : hitlReviews) {
                    String status = review.getStatus();
                    if ("CORRECTED".equals(status)) {
                        // CORRECTED: 使用修正内容作为 hitlOverride，从当前阶段重跑
                        hitlOverride = review.getCorrectedContent();
                        previousOutput = hitlOverride != null ? hitlOverride : previousOutput;
                        phaseResolved = true;
                        log.info("HITL CORRECTED 恢复: executionId={}, phase={}, correctedLen={}",
                                execId, pausedPhase, hitlOverride != null ? hitlOverride.length() : 0);
                        break;
                    } else if ("RESTART_PHASE".equals(status)) {
                        // RESTART_PHASE: 注入重启提示，从当前阶段重跑
                        restartHint = review.getRestartHint();
                        previousOutput = injectResumeRestartHint(execution.getTaskDescription(), restartHint);
                        phaseResolved = true;
                        log.info("HITL RESTART_PHASE 恢复: executionId={}, phase={}, hint={}",
                                execId, pausedPhase, restartHint);
                        break;
                    } else if ("RESTART_AGENT".equals(status)) {
                        // RESTART_AGENT: 清除指定 Agent 检查点，从当前阶段恢复
                        restartAgentName = review.getRestartAgent();
                        clearCheckpointForAgent(execId, pausedPhase, restartAgentName);
                        phaseResolved = true;
                        log.info("HITL RESTART_AGENT 恢复: executionId={}, phase={}, agent={}",
                                execId, pausedPhase, restartAgentName);
                        break;
                    } else if ("APPROVED".equals(status) || "TIMEOUT".equals(status)) {
                        // APPROVED/TIMEOUT: 从审核记录的 outputContent 中取输出作为后续输入
                        if (review.getOutputContent() != null) {
                            previousOutput = review.getOutputContent();
                        }
                    }
                }

                // 非修正/重启类恢复，按现有逻辑推导起始阶段
                int startPhase;
                if (phaseResolved) {
                    // CORRECTED / RESTART_PHASE / RESTART_AGENT 均从当前阶段重跑
                    startPhase = pausedPhase;
                } else {
                    // 正常恢复：检查 Agent 检查点，有则从当前阶段续跑，无则跳到下一阶段
                    startPhase = resolveNormalResumePhase(execId, pausedPhase, execution.getAgentState());
                }

                PhaseCallbacks callbacks = createPhaseCallbacks(startTime, startPhase, accountId, execId,
                        execution.getProjectId());
                PhaseResult result;
                if (hitlOverride != null || restartHint != null) {
                    // CORRECTED / RESTART_PHASE: 通过 ContextPackage 传递修正/重启信息
                    ContextPackage contextPkg = new ContextPackage(previousOutput,
                            execution.getTaskDescription(), Map.of(), hitlOverride, null);
                    result = phaseScheduler.executeSquad(phases, squadMode, contextPkg,
                            execId, accountId, callbacks, startPhase)
                            .doFinally(signal -> cancelledExecutions.remove(execId))
                            .block(Duration.ofMinutes(30));
                } else {
                    result = phaseScheduler.executeSquad(phases, squadMode, previousOutput,
                            execId, accountId, callbacks, startPhase)
                            .doFinally(signal -> cancelledExecutions.remove(execId))
                            .block(Duration.ofMinutes(30));
                }

                if (result != null) {
                    if (result.paused()) {
                        String reason = result.pauseReason();
                        if ("CANCELLED".equals(reason) || "TIMEOUT".equals(reason)) {
                            log.info("Squad 恢复执行终止: executionId={}, reason={}", execId, reason);
                        } else {
                            log.info("Squad 恢复执行暂停: executionId={}, reason={}", execId, reason);
                        }
                    } else {
                        squadExecutionRepository.updateStatus(execId, "COMPLETED", null);
                        squadExecutionRepository.updateResult(execId, result.content(), null);
                        publishEvent(execId, "COMPLETED", phases.size() - 1,
                                null, "Squad 执行完成");
                        squad.recordUsage();
                        squadRepository.update(squad);
                    }
                }
        } catch (Exception e) {
            log.error("Squad 恢复执行失败: executionId={}", execId, e);
            squadExecutionRepository.updateStatus(execId, "FAILED", e.getMessage());
            publishEvent(execId, "FAILED", null, null,
                    "恢复执行失败: " + e.getMessage());
        } finally {
            cancelledExecutions.remove(execId);
        }
    }

    /**
     * 正常恢复（APPROVED/TIMEOUT）时推导起始阶段。
     * 有 Agent 检查点则从当前阶段续跑，否则跳到下一阶段。
     */
    private int resolveNormalResumePhase(UUID execId, int pausedPhase, String agentStateJson) {
        boolean hasCheckpoints = false;
        try {
            if (agentStateJson != null && !agentStateJson.isBlank() && !"{}".equals(agentStateJson)) {
                Map<Integer, Object> parsed = new ObjectMapper().findAndRegisterModules()
                        .readValue(agentStateJson, new TypeReference<Map<Integer, Object>>() {});
                hasCheckpoints = parsed.containsKey(pausedPhase);
            }
        } catch (Exception e) {
            // ignore parse errors, treat as no checkpoints
        }
        int startPhase = hasCheckpoints ? pausedPhase : pausedPhase + 1;
        log.info("正常恢复: hasCheckpoints={}, startPhase={}", hasCheckpoints, startPhase);
        return startPhase;
    }

    /**
     * RESTART_PHASE 时将重启提示注入到任务描述中。
     */
    private static String injectResumeRestartHint(String taskDescription, String restartHint) {
        if (restartHint == null || restartHint.isBlank()) return taskDescription;
        return """
                ⚠️ [RESTART_PHASE — 人工修正提示]
                请根据以下反馈重新执行本阶段：

                %s

                ——— 原始任务描述 ———
                %s
                """.formatted(restartHint, taskDescription != null ? taskDescription : "");
    }

    /**
     * RESTART_AGENT 时从 agent_state 中清除指定 Agent 的检查点。
     */
    private void clearCheckpointForAgent(UUID execId, int phaseIndex, String agentName) {
        if (agentName == null || agentName.isBlank()) return;
        try {
            var currentOpt = squadExecutionRepository.findById(execId);
            if (currentOpt.isEmpty()) return;
            String json = currentOpt.get().getAgentState();
            if (json == null || json.isBlank() || "{}".equals(json)) return;

            ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
            Map<Integer, List<Map<String, Object>>> allCps =
                    mapper.readValue(json, new TypeReference<Map<Integer, List<Map<String, Object>>>>() {});

            List<Map<String, Object>> phaseCps = allCps.get(phaseIndex);
            if (phaseCps == null || phaseCps.isEmpty()) return;

            // 过滤掉指定 agentName 的检查点
            List<Map<String, Object>> remaining = phaseCps.stream()
                    .filter(cp -> !agentName.equals(cp.get("agentName")))
                    .toList();

            if (remaining.size() < phaseCps.size()) {
                if (remaining.isEmpty()) {
                    allCps.remove(phaseIndex);
                } else {
                    allCps.put(phaseIndex, remaining);
                }
                squadExecutionRepository.updateAgentState(execId,
                        mapper.writeValueAsString(allCps));
                log.info("已清除 Agent 检查点: executionId={}, phase={}, agent={}",
                        execId, phaseIndex, agentName);
            }
        } catch (Exception e) {
            log.warn("清除 Agent 检查点失败: executionId={}, phase={}, agent={}",
                    execId, phaseIndex, agentName, e);
        }
    }

    /**
     * 从前一阶段契约中获取输出，作为当前阶段的重试输入。
     * 首阶段无前一阶段，返回原始任务描述。
     */
    private String resolveInputFromPreviousPhase(UUID executionId, int startPhase, String fallback) {
        if (startPhase <= 0) return fallback;
        List<Contract> prevContracts = contractRepository.findByExecutionIdAndPhase(
                executionId, startPhase - 1);
        if (prevContracts.isEmpty()) {
            log.warn("前一阶段无契约记录，使用原始任务描述: executionId={}, phase={}",
                    executionId, startPhase);
            return fallback;
        }
        // 最后一条契约是前一阶段最终输出
        String output = prevContracts.get(prevContracts.size() - 1).getContent();
        log.info("重试注入前一阶段输出: executionId={}, prevPhase={}, contentLen={}",
                executionId, startPhase - 1, output != null ? output.length() : 0);
        return output != null ? output : fallback;
    }

    // ========== 模式分派 ==========

    /**
     * 创建适配引擎的回调策略：LLM 调用 + HITL/取消/超时检查 + 事件发布 + 契约保存。
     *
     * @param startTimeMs 超时检查起点
     * @param startPhase  当前阶段索引（HITL 预检查使用 > startPhase 判断）
     */
    private PhaseCallbacks createPhaseCallbacks(long startTimeMs, int startPhase,
                                                UUID accountId, UUID execId, UUID projectId) {
        return new PhaseCallbacks() {
            private final ObjectMapper checkpointMapper = new ObjectMapper().findAndRegisterModules();

            @Override
            public Mono<LlmResult> callLlm(String userMessage, TokenContext ctx) {
                var defaultModel = modelRouter.getDefaultModel(accountId);
                if (defaultModel == null) {
                    log.warn("无可用模型: accountId={}", accountId);
                    return Mono.just(new LlmResult("", ""));
                }
                return defaultModel.chat(
                        List.of(Msg.of(Role.USER, userMessage)),
                        null,
                        ModelParams.defaults().withTemperature(0.7).withMaxTokens(4096)
                ).map(response -> {
                    String content = response != null ? response.msg().text() : "";
                    return new LlmResult(content, "");
                });
            }

            @Override
            public Mono<LlmResult> callLlmForAgent(String agentId, String userMessage, TokenContext ctx) {
                String systemPrompt = agentRepository.findByAccountAndName(accountId, agentId)
                        .map(agent -> {
                            agent.recordUsage();
                            agentRepository.save(agent);
                            return agent.getSystemPrompt();
                        })
                        .filter(p -> p != null && !p.isBlank())
                        .orElse("");

                // 工具决议：MCP 工具 + 语义匹配工具合并
                mcpConnectionManager.connectAll();
                List<ToolSpec> mcpSpecs = toolProvider.listTools();
                MatchedTools matchedTools = toolResolver.resolveForAgent(agentId, accountId);

                java.util.LinkedHashMap<String, ToolSpec> merged = new java.util.LinkedHashMap<>();
                if (mcpSpecs != null) {
                    for (var s : mcpSpecs) merged.put(s.name(), s);
                }
                if (!matchedTools.isEmpty()) {
                    for (var s : matchedTools.schemas()) {
                        merged.putIfAbsent(s.name(), s);
                    }
                }
                List<ToolSpec> mergedSpecs = new ArrayList<>(merged.values());

                String enrichedPrompt = !matchedTools.isEmpty()
                        ? toolEnricher.enrichPrompt(systemPrompt, matchedTools.metas())
                        : systemPrompt;

                // 构建 Agent 执行
                var langModel = modelRouter.getDefaultModel(accountId);
                if (langModel == null) {
                    log.warn("callLlmForAgent 无可用模型: accountId={}", accountId);
                    return Mono.just(new LlmResult("", ""));
                }
                Agent agent = Agent.builder()
                        .agentId(agentId)
                        .languageModel(langModel)
                        .toolProvider(toolProvider)
                        .skillProvider(new com.icusu.sivan.infra.skill.DbSkillProvider(accountId, skillRepository))
                        .executionStrategy(executionStrategy)
                        .build();

                List<Msg> msgs = List.of(
                        Msg.of(Role.SYSTEM, enrichedPrompt.isBlank() ? "You are a helpful assistant." : enrichedPrompt),
                        Msg.of(Role.USER, userMessage));

                ExecutionContext execCtx = ExecutionContext.create(execId.toString(), msgs,
                        java.util.Map.of(ReActExecutionStrategy.ATTR_TOOLS, mergedSpecs));

                return agent.execute(execCtx).last().map(event -> {
                    if (event instanceof AgentEvent.Completed c) {
                        return new LlmResult(c.result().content(), c.result().thinking());
                    }
                    return new LlmResult("", "");
                });
            }

            @Override
            public Mono<LlmResult> callReactAgent(String agentId, String userMessage, TokenContext ctx) {
                String systemPrompt = agentRepository.findByAccountAndName(accountId, agentId)
                        .map(AgentDefinition::getSystemPrompt)
                        .filter(p -> !p.isBlank())
                        .orElse("");

                // 工具决议：MCP + 语义匹配
                MatchedTools matched = toolResolver.resolveForAgent(agentId, accountId);
                mcpConnectionManager.connectAll();
                List<ToolSpec> mcpSpecs = toolProvider.listTools();

                java.util.LinkedHashMap<String, ToolSpec> merged = new java.util.LinkedHashMap<>();
                if (mcpSpecs != null) {
                    for (var s : mcpSpecs) merged.put(s.name(), s);
                }
                if (!matched.isEmpty()) {
                    for (var s : matched.schemas()) {
                        merged.putIfAbsent(s.name(), s);
                    }
                }
                List<ToolSpec> mergedSpecs = new ArrayList<>(merged.values());

                String enrichedPrompt = !matched.isEmpty()
                        ? toolEnricher.enrichPrompt(systemPrompt, matched.metas())
                        : systemPrompt;

                // 构建 Agent（中心入口）
                var langModel = modelRouter.getDefaultModel(accountId);
                if (langModel == null) {
                    log.warn("callReactAgent 无可用模型: accountId={}", accountId);
                    return Mono.just(new LlmResult("", ""));
                }
                Agent agent = Agent.builder()
                        .agentId(agentId)
                        .languageModel(langModel)
                        .toolProvider(toolProvider)
                        .skillProvider(new com.icusu.sivan.infra.skill.DbSkillProvider(accountId, skillRepository))
                        .executionStrategy(executionStrategy)
                        .build();

                List<Msg> coreMsgs = new ArrayList<>();
                coreMsgs.add(Msg.of(Role.SYSTEM,
                        enrichedPrompt.isBlank() ? "You are a helpful assistant." : enrichedPrompt));
                coreMsgs.add(Msg.of(Role.USER, userMessage));

                ExecutionContext execCtx = ExecutionContext.create(execId.toString(), coreMsgs,
                        java.util.Map.of(ReActExecutionStrategy.ATTR_TOOLS, mergedSpecs));

                // 响应式执行 Agent，同时映射 AgentEvent → publishEvent 流式推送
                return agent.execute(execCtx)
                        .<LlmStrategy.LlmResult>reduce(new LlmStrategy.LlmResult("", ""), (acc, event) -> {
                            String content = acc.content();
                            String thinking = acc.thinking();
                            switch (event) {
                                case AgentEvent.Chunk c -> {
                                    publishEvent(execId, "STREAMING", null, agentId, c.delta());
                                    content += c.delta();
                                }
                                case AgentEvent.Thinking t -> {
                                    publishEvent(execId, "STREAMING", null, agentId, t.content());
                                    thinking += t.content();
                                }
                                case AgentEvent.Completed c -> {
                                    var r = c.result();
                                    return new LlmStrategy.LlmResult(
                                            r.content() != null ? r.content() : content,
                                            r.thinking() != null ? r.thinking() : thinking);
                                }
                                default -> {}
                            }
                            return new LlmStrategy.LlmResult(content, thinking);
                        });
            }

            @Override
            public void saveContract(UUID executionId, UUID accountId, int phaseIndex,
                                     String sourceAgent, String targetAgent, String content) {
                contractRepository.save(ContractFactory.create(executionId, accountId, projectId,
                        phaseIndex, sourceAgent, targetAgent, content));
            }

            @Override
            public void publishEvent(UUID eid, String status, Integer phase,
                                     String phaseName, String message) {
                eventSink.tryEmitNext(new SquadExecutionEvent(eid, status, phase, phaseName, message));
            }

            @Override
            public TokenContext createTokenContext(UUID acctId, UUID executionId) {
                return buildTokenContext(acctId, executionId, projectId);
            }

            @Override
            public boolean isCancelled(UUID eid) {
                return Boolean.TRUE.equals(cancelledExecutions.get(eid));
            }

            @Override
            public boolean isTimedOut(UUID eid) {
                return System.currentTimeMillis() - startTimeMs > EXECUTION_TIMEOUT_MS;
            }

            @Override
            public void saveAgentCheckpoints(UUID executionId, int phaseIndex, List<AgentCheckpoint> checkpoints) {
                try {
                    // 读出已有的 agent_state，合并当前阶段的检查点后写回
                    var currentOpt = squadExecutionRepository.findById(executionId);
                    if (currentOpt.isEmpty()) return;
                    String existingJson = currentOpt.get().getAgentState();
                    Map<Integer, List<AgentCheckpoint>> allCps = new HashMap<>();
                    if (existingJson != null && !existingJson.isBlank() && !"{}".equals(existingJson)) {
                        allCps = checkpointMapper.readValue(existingJson,new TypeReference<>() {});
                    }
                    allCps.put(phaseIndex, checkpoints);
                    squadExecutionRepository.updateAgentState(executionId,
                            checkpointMapper.writeValueAsString(allCps));
                } catch (Exception e) {
                    log.warn("保存 Agent 检查点失败: executionId={}, phaseIndex={}",
                            executionId, phaseIndex, e);
                }
            }

            @Override
            public List<AgentCheckpoint> loadAgentCheckpoints(UUID executionId, int phaseIndex) {
                try {
                    var currentOpt = squadExecutionRepository.findById(executionId);
                    if (currentOpt.isEmpty()) return List.of();
                    String json = currentOpt.get().getAgentState();
                    if (json == null || json.isBlank() || "{}".equals(json)) return List.of();
                    Map<Integer, List<AgentCheckpoint>> allCps = checkpointMapper.readValue(json,
                            new TypeReference<Map<Integer, List<AgentCheckpoint>>>() {});
                    return allCps.getOrDefault(phaseIndex, List.of());
                } catch (Exception e) {
                    log.warn("加载 Agent 检查点失败: executionId={}, phaseIndex={}",
                            executionId, phaseIndex, e);
                    return List.of();
                }
            }

            @Override
            public PhaseResult preDispatchPhase(PhaseNode phase, int phaseIndex,
                                                String input, UUID eid) {
                String phaseName = phase.getName() != null ? phase.getName() : "阶段 " + phaseIndex;

                // HITL 预暂停：PRE 或 ALL 模式且未在当前恢复批次的首位
                String hitlMode = PhaseExecutor.resolveHitlMode(phase);
                if ((PhaseExecutor.HITL_PRE.equals(hitlMode) || PhaseExecutor.HITL_ALL.equals(hitlMode))
                        && phaseIndex > startPhase) {
                    saveHitlReview(eid, accountId, phaseIndex, phaseName, input, null);
                    squadExecutionRepository.updateCurrentPhase(eid, phaseIndex);
                    squadExecutionRepository.updateStatus(eid, "HITL_PENDING",
                            "等待人工审核: " + phaseName);
                    publishEvent(eid, "HITL_PENDING", phaseIndex, phaseName, "等待人工审核");
                    return PhaseResult.paused(input, "等待人工审核: " + phaseName);
                }

                // 发布阶段开始事件
                publishEvent(eid, "RUNNING", phaseIndex, phaseName, "开始阶段: " + phaseName);
                return null;
            }

            @Override
            public void onPhasePaused(PhaseNode phase, int phaseIndex,
                                      String input, PhaseResult result) {
                String phaseName = phase.getName() != null ? phase.getName() : "阶段 " + phaseIndex;
                saveHitlReview(execId, accountId, phaseIndex, phaseName, input, result.content());
                squadExecutionRepository.updateCurrentPhase(execId, phaseIndex);
                squadExecutionRepository.updateStatus(execId, "HITL_PENDING",
                        "暂停: " + result.pauseReason());
            }

            @Override
            public void onArtifactGenerated(UUID eid, int phaseIndex, PhaseOutput output) {
                if (output.artifacts() == null || output.artifacts().isEmpty()) return;
                output.artifacts().forEach((name, content) -> {
                    ExecutionArtifact artifact = new ExecutionArtifact(
                            eid, phaseIndex, name, content, ArtifactType.DOC);
                    executionArtifactRepository.save(artifact);
                    log.debug("产物已持久化: executionId={}, phase={}, name={}", eid, phaseIndex, name);
                });
            }
        };
    }

    // ========== 辅助方法 ==========

    /**
     * 构建 Token 消耗跟踪上下文。
     */
    private TokenContext buildTokenContext(UUID accountId, UUID executionId, UUID projectId) {
        return TokenContext.builder()
                .accountId(accountId)
                .projectId(projectId)
                .source(TokenSource.SQUAD_EXECUTION)
                .conversationId(executionId)
                .build();
    }

    /**
     * 创建 HITL 人工审核记录（含等待超时时间）。
     */
    private void saveHitlReview(UUID executionId, UUID accountId, int phase,
                                String phaseName, String inputContent, String outputContent) {
        HitlReview review = HitlReview.builder()
                .executionId(executionId)
                .accountId(accountId)
                .phase(phase)
                .phaseName(phaseName)
                .inputContent(inputContent)
                .outputContent(outputContent)
                .status("PENDING")
                .expiresAt(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(HitlReview.DEFAULT_TIMEOUT_MINUTES))
                .build();
        hitlReviewRepository.save(review);
    }

    /**
     * 通过 Sink 发布执行进度事件。
     */
    private void publishEvent(UUID executionId, String status, Integer phase,
                              String phaseName, String message) {
        eventSink.tryEmitNext(new SquadExecutionEvent(executionId, status, phase, phaseName, message));
    }
}
