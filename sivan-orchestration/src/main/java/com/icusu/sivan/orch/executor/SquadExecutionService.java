package com.icusu.sivan.orch.executor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.icusu.sivan.agent.model.ModelRouter;
import com.icusu.sivan.agent.prompt.ToolPrompts;
import com.icusu.sivan.agent.prompt.ToolPrompts.ToolMetaProjection;
import com.icusu.sivan.agent.routing.RoutingDecisionRecorder;
import com.icusu.sivan.agent.routing.RoutingDecisionRecorder.RecordRequest;
import com.icusu.sivan.agent.service.TokenUsageRecorder;
import com.icusu.sivan.agent.strategy.ReActExecutionStrategy;
import com.icusu.sivan.agent.tool.MatchedTools;
import com.icusu.sivan.agent.tool.ToolEnricher;
import com.icusu.sivan.agent.tool.ToolResolver;
import com.icusu.sivan.common.enums.GoalStatus;
import com.icusu.sivan.common.enums.SquadMode;
import com.icusu.sivan.common.enums.AutoMode;
import com.icusu.sivan.common.enums.TokenSource;
import com.icusu.sivan.core.agent.Agent;
import com.icusu.sivan.core.agent.AgentEvent;
import com.icusu.sivan.core.agent.ExecutionStrategy;
import com.icusu.sivan.core.context.ExecutionContext;
import com.icusu.sivan.core.message.Msg;
import com.icusu.sivan.core.message.Role;
import com.icusu.sivan.core.model.Model.ModelParams;
import com.icusu.sivan.core.tool.ToolProvider;
import com.icusu.sivan.core.tool.ToolSpec;
import com.icusu.sivan.domain.agent.AgentDefinition;
import com.icusu.sivan.domain.agent.IAgentRepository;
import com.icusu.sivan.domain.agent.ISkillRepository;
import com.icusu.sivan.domain.feedback.IPatternFeedbackRepository;
import com.icusu.sivan.domain.feedback.PatternFeedbackRecord;
import com.icusu.sivan.domain.goal.Goal;
import com.icusu.sivan.domain.goal.IGoalRepository;
import com.icusu.sivan.domain.goal.Milestone;
import com.icusu.sivan.domain.goal.Task;
import com.icusu.sivan.domain.orchestration.*;
import com.icusu.sivan.domain.shared.vo.TokenContext;
import com.icusu.sivan.infra.skill.DbSkillProvider;
import com.icusu.sivan.orch.contract.ContractFactory;
import com.icusu.sivan.orch.hitl.HitlService;
import com.icusu.sivan.orch.topology.SquadCreator;
import com.icusu.sivan.memory.instinct.InstinctPatternService;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

/**
 * Squad 执行服务。处理 Squad 匹配、创建、同步执行的全流程。
 * 被 {@link SquadStrategy} 和 {@link SquadOrchestrator} 共用。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SquadExecutionService {

    private final ISquadRepository squadRepository;
    private final ISquadExecutionRepository squadExecutionRepository;
    private final IContractRepository contractRepository;
    private final ToolProvider toolProvider;
    private final ExecutionStrategy executionStrategy;
    private final SquadCreator squadCreator;
    private final IAgentRepository agentRepository;
    private final RoutingDecisionRecorder routingDecisionRecorder;
    private final PhaseScheduler phaseScheduler;
    private final HitlService hitlService;
    private final ToolResolver toolAutoResolver;
    private final ModelRouter modelRouter;
    private final SquadMatcher squadMatcher;
    private final ToolEnricher toolEnricher;
    private final TokenUsageRecorder tokenUsageRecorder;
    private final ISkillRepository skillRepository;
    private final IGoalRepository goalRepository;
    private final IExecutionArtifactRepository executionArtifactRepository;
    private final IPatternFeedbackRepository patternFeedbackRepository;
    private final InstinctPatternService instinctPatternService;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 匹配 Squad 并同步执行，通过 sink 发射事件。
     */
    public void handleSquad(String taskDescription, UUID accountId,
                            String historyContext, UUID projectId, UUID conversationId,
                            FluxSink<OrchestrationEvent> sink,
                            List<ToolSpec> mcpTools,
                            String fileRootPath, boolean archived) {
        long totalStartMs = System.currentTimeMillis();
        String modelName = resolveModelName(accountId);

        sink.next(OrchestrationEvent.stepStart("match_squad", "正在匹配 Squad..."));

        List<Squad> squads = squadRepository.findAllByAccountAndActiveTrue(accountId);
        Squad matched = matchSquad(taskDescription, squads);

        if (matched == null) {
            log.info("未找到匹配的 Squad，自动创建并执行编排: {}", truncateStr(taskDescription, 60));
            sink.next(OrchestrationEvent.stepEnd("match_squad", "未匹配到现有 Squad，开始自动创建"));
            createSquadStream(taskDescription, accountId, projectId, conversationId, sink, historyContext)
                    .flatMap(created -> {
                        // 执行前创建 Goal 蓝图
                        Goal goal = createGoalBlueprint(created, taskDescription, accountId,
                                created.getProjectId() != null ? created.getProjectId() : projectId,
                                conversationId, fileRootPath);
                        UUID goalId = goal != null ? goal.getGoalId() : null;

                        String snapshot = buildTopologySnapshot(created);
                        SquadExecution execution = SquadExecution.builder()
                                .squadId(created.getSquadId())
                                .accountId(accountId)
                                .projectId(created.getProjectId())
                                .taskDescription(taskDescription)
                                .status(null)
                                .topologySnapshot(snapshot)
                                .currentPhase(0)
                                .context(new HashMap<>())
                                .startedAt(LocalDateTime.now(ZoneOffset.UTC))
                                .build();
                        squadExecutionRepository.save(execution);

                        UUID execId = execution.getExecutionId();
                        return executeSquadSync(created, execution, accountId, historyContext, sink, mcpTools, fileRootPath, archived)
                                .doOnNext(squadResult -> {
                                    squadExecutionRepository.updateResult(execId,
                                            squadResult.content(), squadResult.thinking());
                                    if (squadResult.paused()) {
                                        sink.next(OrchestrationEvent.stepEnd("execute_squad",
                                                "执行已暂停: " + squadResult.pauseReason(),
                                                Map.of("paused", true, "executionId", execId.toString())));
                                        sink.next(OrchestrationEvent.complete(Map.of(
                                                "type", "squad_paused", "content", "执行已暂停：" + squadResult.pauseReason(),
                                                "executionId", execId.toString())));
                                    } else {
                                        long totalMs = System.currentTimeMillis() - totalStartMs;
                                        sink.next(OrchestrationEvent.stepEnd("execute_squad", "执行完成",
                                                Map.of("durationMs", squadResult.totalDurationMs())));
                                        Map<String, Object> meta = new HashMap<>();
                                        meta.put("type", "squad");
                                        meta.put("content", squadResult.content());
                                        meta.put("thinking", squadResult.thinking());
                                        meta.put("durationMs", totalMs);
                                        meta.put("model", modelName);
                                        meta.put("executionId", execId.toString());
                                        // 注入 Squad 中所有智能体名称，供路由决策展示
                                        List<String> agentNames = extractAgentNames(created);
                                        if (!agentNames.isEmpty()) {
                                            meta.put("agentNames", agentNames);
                                        }
                                        if (goalId != null) {
                                            meta.put("goalId", goalId.toString());
                                        }
                                        sink.next(OrchestrationEvent.complete(meta));
                                    }
                                })
                                .doOnError(error -> {
                                    log.error("Squad 同步执行异常", error);
                                    sink.next(OrchestrationEvent.complete(Map.of(
                                            "type", "chat", "content", toUserMessage("Squad 执行失败", error))));
                                })
                                .doFinally(signal -> sink.complete());
                    })
                    .subscribe(null, error -> log.error("Squad 创建并执行异常", error));
            return;
        }

        routingDecisionRecorder.record(RecordRequest.simple(
                accountId, projectId, conversationId, taskDescription,
                "SQUAD_MATCH", matched.getName(), true,
                "匹配到 Squad: " + matched.getName() + " (SquadMode=" + matched.getMode() + ")",
                Map.of("squadId", matched.getSquadId().toString())));

        log.info("匹配到 Squad: {} ({})", matched.getName(), matched.getSquadId());
        sink.next(OrchestrationEvent.stepEnd("match_squad", "已匹配 Squad：「" + matched.getName() + "」"));

        // 执行前创建 Goal 蓝图
        Goal goal = createGoalBlueprint(matched, taskDescription, accountId,
                matched.getProjectId() != null ? matched.getProjectId() : projectId,
                conversationId, fileRootPath);
        UUID goalId = goal != null ? goal.getGoalId() : null;

        String snapshot = buildTopologySnapshot(matched);
        SquadExecution execution = SquadExecution.builder()
                .squadId(matched.getSquadId())
                .accountId(accountId)
                .projectId(matched.getProjectId())
                .taskDescription(taskDescription)
                .status(null)
                .topologySnapshot(snapshot)
                .currentPhase(0)
                .context(new HashMap<>())
                .startedAt(LocalDateTime.now(ZoneOffset.UTC))
                .build();
        squadExecutionRepository.save(execution);

        sink.next(OrchestrationEvent.stepStart("execute_squad", "正在执行 Squad「" + matched.getName() + "」...",
                Map.of("executionId", execution.getExecutionId().toString(),
                        "squadName", matched.getName() != null ? matched.getName() : "")));
        UUID execId = execution.getExecutionId();
        executeSquadSync(matched, execution, accountId, historyContext, sink, mcpTools, fileRootPath, archived)
                .doOnNext(squadResult -> {
                    squadExecutionRepository.updateResult(execId,
                            squadResult.content(), squadResult.thinking());
                    if (squadResult.paused()) {
                        sink.next(OrchestrationEvent.stepEnd("execute_squad",
                                "Squad 执行已暂停: " + squadResult.pauseReason(),
                                Map.of("paused", true, "executionId", execId.toString())));
                        sink.next(OrchestrationEvent.complete(Map.of(
                                "type", "squad_paused",
                                "content", "Squad 执行已暂停：" + squadResult.pauseReason(),
                                "executionId", execId.toString())));
                    } else {
                        long totalMs = System.currentTimeMillis() - totalStartMs;
                        sink.next(OrchestrationEvent.stepEnd("execute_squad", "Squad 执行完成",
                                Map.of("durationMs", squadResult.totalDurationMs())));
                        Map<String, Object> meta = new HashMap<>();
                        meta.put("type", "squad");
                        meta.put("content", squadResult.content());
                        meta.put("thinking", squadResult.thinking());
                        meta.put("durationMs", totalMs);
                        meta.put("model", modelName);
                        meta.put("executionId", execId.toString());
                        // 注入 Squad 中所有智能体名称，供路由决策展示
                        List<String> agentNames = extractAgentNames(matched);
                        if (!agentNames.isEmpty()) {
                            meta.put("agentNames", agentNames);
                        }
                        if (goalId != null) {
                            meta.put("goalId", goalId.toString());
                        }
                        sink.next(OrchestrationEvent.complete(meta));
                    }
                })
                .doOnError(error -> {
                    log.error("Squad 同步执行异常", error);
                    sink.next(OrchestrationEvent.complete(Map.of(
                            "type", "chat", "content", toUserMessage("Squad 执行失败", error))));
                })
                .doFinally(signal -> sink.complete())
                .subscribe();
    }

    @Builder
    private record SquadExecResult(String content, String thinking, long totalDurationMs, boolean paused,
                                   String pauseReason) {
    }

    private Mono<SquadExecResult> executeSquadSync(Squad squad, SquadExecution execution, UUID accountId,
                                                   String historyContext, FluxSink<OrchestrationEvent> sink,
                                                   List<ToolSpec> mcpTools,
                                                   String fileRootPath, boolean archived) {
        List<PhaseNode> phases = squad.getPhases();
        if (phases == null || phases.isEmpty()) {
            return Mono.just(SquadExecResult.builder().content("Squad 没有定义阶段。").build());
        }

        squadExecutionRepository.updateStatus(execution.getExecutionId(), "RUNNING", null);
        String input = (historyContext != null && !historyContext.isBlank())
                ? historyContext + "\n\n## 当前任务\n" + execution.getTaskDescription()
                : execution.getTaskDescription();

        StringBuffer allThinking = new StringBuffer();
        long totalStartMs = System.currentTimeMillis();
        UUID execId = execution.getExecutionId();

        SquadMode squadMode = squad.getMode() != null ? squad.getMode() : SquadMode.SEQUENTIAL;
        PhaseCallbacks callbacks = createPhaseCallbacks(execution, allThinking, sink, mcpTools, fileRootPath, archived);

        return phaseScheduler.executeSquad(phases, squadMode, input, execId, accountId, callbacks)
                .map(result -> {
                    if (result.paused()) {
                        log.info("Squad 响应式执行暂停: reason={}", result.pauseReason());
                        return SquadExecResult.builder()
                                .content(result.content())
                                .thinking(allThinking.toString())
                                .totalDurationMs(System.currentTimeMillis() - totalStartMs)
                                .paused(true)
                                .pauseReason(result.pauseReason())
                                .build();
                    }
                    squadExecutionRepository.updateStatus(execId, "COMPLETED", null);
                    recordExecutionOutcome(execution, PatternFeedbackRecord.FeedbackOutcome.SUCCESS, null, squad);
                    return SquadExecResult.builder()
                            .content(result.content())
                            .thinking(allThinking.toString())
                            .totalDurationMs(System.currentTimeMillis() - totalStartMs)
                            .build();
                })
                .onErrorResume(e -> {
                    log.error("Squad 执行异常: squadId={}", squad.getSquadId(), e);
                    squadExecutionRepository.updateStatus(execId, "FAILED",
                            e.getMessage() != null ? truncateStr(e.getMessage(), 500) : "未知错误");
                    recordExecutionOutcome(execution, PatternFeedbackRecord.FeedbackOutcome.FAILURE,
                            e.getMessage(), squad);
                    return Mono.just(SquadExecResult.builder()
                            .content(toUserMessage("Squad 执行失败", e))
                            .thinking(allThinking.toString())
                            .totalDurationMs(System.currentTimeMillis() - totalStartMs)
                            .build());
                });
    }

    private PhaseCallbacks createPhaseCallbacks(SquadExecution execution, StringBuffer allThinking,
                                                FluxSink<OrchestrationEvent> sink,
                                                List<ToolSpec> mcpTools,
                                                String fileRootPath, boolean archived) {
        return new PhaseCallbacks() {
            private final String modelName = resolveModelName(execution.getAccountId());

            @Override
            public Mono<LlmStrategy.LlmResult> callLlm(String userMessage, TokenContext ctx) {
                return modelRouter.getDefaultModel(execution.getAccountId()).chat(
                        List.of(Msg.of(Role.USER, userMessage)),
                        null,
                        ModelParams.defaults().withTemperature(0.7).withMaxTokens(4096)
                ).map(response -> {
                    String content = response != null ? response.msg().text() : "";
                    String thinking = response != null ? response.msg().thinking() : "";
                    return new LlmStrategy.LlmResult(content, thinking != null ? thinking : "");
                });
            }

            @Override
            public Mono<LlmStrategy.LlmResult> callLlmForAgent(String agentId, String userMessage, TokenContext ctx) {
                var agentOpt = agentRepository.findByAccountAndName(
                        execution.getAccountId(), agentId);
                String systemPrompt = agentOpt.map(AgentDefinition::getSystemPrompt)
                        .filter(p -> !p.isBlank())
                        .orElse("");
                UUID agentUuid = agentOpt.map(AgentDefinition::getAgentId).orElse(null);
                agentOpt.ifPresent(agent -> {
                    agent.recordUsage();
                    agentRepository.save(agent);
                });
                recordSkillUsage(agentId);
                TokenContext agentCtx = agentUuid != null ? TokenContext.builder()
                        .accountId(ctx.getAccountId()).projectId(ctx.getProjectId())
                        .source(ctx.getSource()).conversationId(ctx.getConversationId())
                        .agentId(agentUuid).build() : ctx;
                MatchedTools matched = toolAutoResolver.resolveForAgent(agentId, execution.getAccountId());
                String toolText;
                if (!matched.isEmpty()) {
                    toolText = toolEnricher.enrichPrompt("", matched.metas());
                } else if (mcpTools != null && !mcpTools.isEmpty()) {
                    List<ToolMetaProjection> projections = new java.util.ArrayList<>();
                    for (var ts : mcpTools) {
                        projections.add(new ToolMetaProjection(
                                ts.name(), ts.description(), "MCP"));
                    }
                    toolText = ToolPrompts.toolEnrichment(projections).content();
                } else {
                    toolText = "";
                }
                StringBuilder merged = new StringBuilder();
                if (!systemPrompt.isBlank()) merged.append(systemPrompt);
                if (!toolText.isBlank()) merged.append(toolText);
                if (!merged.isEmpty()) merged.append("\n\n");
                merged.append(userMessage);
                return callLlm(merged.toString(), agentCtx);
            }

            @Override
            public Mono<LlmStrategy.LlmResult> callReactAgent(String agentId, String userMessage, TokenContext ctx) {
                var agentOpt = agentRepository.findByAccountAndName(
                        execution.getAccountId(), agentId);
                String systemPrompt = agentOpt.map(AgentDefinition::getSystemPrompt)
                        .filter(p -> !p.isBlank())
                        .orElse("");
                UUID agentUuid = agentOpt.map(AgentDefinition::getAgentId).orElse(null);
                agentOpt.ifPresent(agent -> {
                    agent.recordUsage();
                    agentRepository.save(agent);
                });
                TokenContext agentCtx = agentUuid != null ? TokenContext.builder()
                        .accountId(ctx.getAccountId()).projectId(ctx.getProjectId())
                        .source(ctx.getSource()).conversationId(ctx.getConversationId())
                        .agentId(agentUuid).build() : ctx;

                MatchedTools matched = toolAutoResolver.resolveForAgent(agentId, execution.getAccountId());
                java.util.LinkedHashMap<String, ToolSpec> mergedTools = new java.util.LinkedHashMap<>();
                if (mcpTools != null) {
                    for (var s : mcpTools) mergedTools.put(s.name(), s);
                }
                if (!matched.isEmpty()) {
                    for (var s : matched.schemas()) {
                        mergedTools.putIfAbsent(s.name(),
                                new ToolSpec(s.name(), s.description(), s.inputSchema()));
                    }
                }
                List<ToolSpec> toolSpecs = new ArrayList<>(mergedTools.values());

                String enrichedPrompt = !matched.isEmpty()
                        ? toolEnricher.enrichPrompt(systemPrompt, matched.metas())
                        : systemPrompt;

                Agent agent = Agent.builder()
                        .agentId(agentId)
                        .languageModel(modelRouter.getDefaultModel(execution.getAccountId()))
                        .toolProvider(toolProvider)
                        .skillProvider(new DbSkillProvider(execution.getAccountId(), skillRepository))
                        .executionStrategy(executionStrategy)
                        .build();

                List<Msg> msgs = new ArrayList<>();
                msgs.add(Msg.of(Role.SYSTEM,
                        enrichedPrompt.isBlank() ? "You are a helpful assistant." : enrichedPrompt));
                msgs.add(Msg.of(Role.USER, userMessage));

                java.util.Map<String, Object> attrs = new java.util.HashMap<>();
                attrs.put(ReActExecutionStrategy.ATTR_TOOLS, toolSpecs);
                attrs.put("_fileRootPath", fileRootPath != null ? fileRootPath : "");
                attrs.put("_archived", archived);
                ExecutionContext execCtx = ExecutionContext.create(
                        execution.getExecutionId().toString(), msgs, attrs);

                return agent.execute(execCtx)
                        .<LlmStrategy.LlmResult>reduce(new LlmStrategy.LlmResult("", ""), (acc, event) -> {
                            String content = acc.content();
                            String thinking = acc.thinking();
                            switch (event) {
                                case AgentEvent.Chunk c -> {
                                    sink.next(OrchestrationEvent.stream(c.delta()));
                                    content += c.delta();
                                }
                                case AgentEvent.Thinking t -> {
                                    sink.next(OrchestrationEvent.streamThinking(t.content()));
                                    thinking += t.content();
                                }
                                case AgentEvent.Completed c -> {
                                    var r = c.result();
                                    if (r.usage() != null && r.usage().totalTokens() > 0) {
                                        tokenUsageRecorder.saveUsage(r.usage(), agentCtx, modelName);
                                    }
                                    return new LlmStrategy.LlmResult(
                                            r.content() != null ? r.content() : content,
                                            r.thinking() != null ? r.thinking() : thinking);
                                }
                                default -> {
                                }
                            }
                            return new LlmStrategy.LlmResult(content, thinking);
                        });
            }

            @Override
            public void afterAgentLlm(String agentLabel, LlmStrategy.LlmResult result) {
                if (result.thinking() != null && !result.thinking().isBlank()) {
                    if (!allThinking.isEmpty()) allThinking.append("\n\n");
                    allThinking.append(result.thinking());
                }
                sink.next(OrchestrationEvent.stepEnd("agent_llm", "智能体「" + agentLabel + "」已完成分析",
                        Map.of("agent", agentLabel, "agentName", agentLabel,
                                "model", modelName, "output", result.content())));
            }

            private void recordSkillUsage(String agentId) {
                try {
                    MatchedTools matched = toolAutoResolver.resolveForAgent(agentId, execution.getAccountId());
                    if (!matched.isEmpty()) {
                        for (var meta : matched.metas()) {
                            skillRepository.findByAccountAndName(execution.getAccountId(), meta.getToolName())
                                    .ifPresent(skill -> {
                                        skill.recordUsage();
                                        skillRepository.save(skill);
                                    });
                        }
                    }
                } catch (Exception e) {
                    log.warn("记录技能使用次数失败: agentId={}, {}", agentId, e.getMessage());
                }
            }

            @Override
            public PhaseResult preDispatchPhase(PhaseNode phase, int phaseIndex, String input, UUID executionId) {
                String phaseName = phase.getName() != null ? phase.getName() : "阶段 " + phaseIndex;
                sink.next(OrchestrationEvent.stepStart("phase", "正在执行「" + phaseName + "」...",
                        Map.of("phaseIndex", phaseIndex, "phaseName", phaseName,
                                "executionId", executionId.toString(),
                                "mode", phase.getMode() != null ? phase.getMode().name() : "SEQUENTIAL")));
                return null;
            }

            @Override
            public void onPhaseCompleted(PhaseNode phase, int phaseIndex, String output, long durationMs) {
                String phaseName = phase.getName() != null ? phase.getName() : "阶段 " + phaseIndex;
                String truncatedOutput = truncateStr(output, 2000);
                sink.next(OrchestrationEvent.stepEnd("phase", "阶段「" + phaseName + "」执行完成",
                        Map.of("phaseIndex", phaseIndex, "phaseName", phaseName,
                                "executionId", execution.getExecutionId().toString(),
                                "durationMs", durationMs, "output", truncatedOutput)));
            }

            @Override
            public void onPhasePaused(PhaseNode phase, int phaseIndex, String input, PhaseResult result) {
                String phaseName = phase.getName() != null ? phase.getName() : "阶段 " + phaseIndex;
                hitlService.createReview(execution.getAccountId(), execution.getExecutionId(),
                        phaseIndex, phaseName, input, result.content());
            }

            @Override
            public void saveContract(UUID execId, UUID acctId, int phaseIndex,
                                     String sourceAgent, String targetAgent, String content) {
                contractRepository.save(ContractFactory.create(execId, acctId, execution.getProjectId(),
                        phaseIndex, sourceAgent, targetAgent, content));
                String summary = sanitizeSseContent(content);
                sink.next(OrchestrationEvent.stepEnd("contract",
                        sourceAgent + " → " + (targetAgent != null ? targetAgent : "广播"),
                        Map.of("phaseIndex", phaseIndex, "content", summary != null ? summary : "")));
            }

            @Override
            public TokenContext createTokenContext(UUID acctId, UUID execId) {
                return TokenContext.builder()
                        .accountId(acctId)
                        .projectId(execution.getProjectId())
                        .source(TokenSource.SQUAD_EXECUTION)
                        .conversationId(execId)
                        .build();
            }

            @Override
            public void onArtifactGenerated(UUID eid, int phaseIndex, PhaseOutput output) {
                if (output.artifacts() == null || output.artifacts().isEmpty()) return;
                output.artifacts().forEach((name, content) -> {
                    ExecutionArtifact artifact = new ExecutionArtifact(
                            eid, phaseIndex, name, content, ArtifactType.DOC);
                    executionArtifactRepository.save(artifact);
                });
            }

            @Override
            public void saveAgentCheckpoints(UUID executionId, int phaseIndex,
                                             List<AgentCheckpoint> checkpoints) {
                try {
                    Map<String, Object> state = new HashMap<>();
                    state.put("phaseIndex", phaseIndex);
                    state.put("checkpoints", checkpoints);
                    String json = MAPPER.writeValueAsString(state);
                    squadExecutionRepository.updateAgentState(executionId, json);
                    log.debug("保存 Agent 检查点: executionId={}, phaseIndex={}, count={}",
                            executionId, phaseIndex, checkpoints.size());
                } catch (Exception e) {
                    log.warn("序列化 Agent 检查点失败: executionId={}", executionId, e);
                }
            }

            @Override
            public List<AgentCheckpoint> loadAgentCheckpoints(UUID executionId, int phaseIndex) {
                try {
                    var opt = squadExecutionRepository.findById(executionId);
                    if (opt.isEmpty()) return List.of();
                    String agentState = opt.get().getAgentState();
                    if (agentState == null || agentState.isBlank()) return List.of();
                    var root = MAPPER.readTree(agentState);
                    if (!root.has("checkpoints") || root.get("phaseIndex").asInt() != phaseIndex) {
                        return List.of();
                    }
                    var arr = root.get("checkpoints");
                    List<AgentCheckpoint> result = new ArrayList<>();
                    for (var node : arr) {
                        result.add(new AgentCheckpoint(
                                node.get("agentIndex").asInt(),
                                node.get("agentName").asText(),
                                node.get("status").asText(),
                                node.has("output") ? node.get("output").asText() : null,
                                node.has("mode") && !node.get("mode").isNull()
                                        ? node.get("mode").asText() : null
                        ));
                    }
                    return result;
                } catch (Exception e) {
                    log.warn("反序列化 Agent 检查点失败: executionId={}", executionId, e);
                    return List.of();
                }
            }
        };
    }

    private String resolveModelName(UUID accountId) {
        try {
            return modelRouter.getDefaultModel(accountId).modelId();
        } catch (Exception e) {
            log.warn("获取模型名称失败: {}", e.getMessage());
            return "unknown";
        }
    }

    private Mono<Squad> createSquadStream(String taskDescription, UUID accountId, UUID projectId,
                                          UUID conversationId, FluxSink<OrchestrationEvent> sink) {
        return createSquadStream(taskDescription, accountId, projectId, conversationId, sink, null);
    }

    private Mono<Squad> createSquadStream(String taskDescription, UUID accountId, UUID projectId,
                                          UUID conversationId, FluxSink<OrchestrationEvent> sink,
                                          String historyContext) {
        return squadCreator.createSquadStream(taskDescription, accountId, projectId, conversationId, sink, historyContext);
    }

    private Squad matchSquad(String taskDescription, List<Squad> squads) {
        return squadMatcher.match(taskDescription, squads);
    }

    private static String buildTopologySnapshot(Squad squad) {
        if (squad.getPhases() == null) return "[]";
        try {
            return MAPPER.writeValueAsString(squad.getPhases());
        } catch (Exception e) {
            return "[]";
        }
    }

    /**
     * 从 Squad 创建 Goal 蓝图（执行前调用）。
     * Squad 每完成一个 Phase，推进 Goal 的一个 Milestone。
     */
    private Goal createGoalBlueprint(Squad squad, String taskDescription,
                                     UUID accountId, UUID projectId,
                                     UUID conversationId, String fileRootPath) {
        List<PhaseNode> phases = squad.getPhases();
        if (phases == null || phases.isEmpty()) return null;

        List<Milestone> milestones = new ArrayList<>();
        int totalTasks = 0;
        String topologyJson = buildTopologySnapshot(squad);

        for (int pIdx = 0; pIdx < phases.size(); pIdx++) {
            PhaseNode phase = phases.get(pIdx);
            List<String> agentNames = phase.getAgents();
            List<Task> tasks = new ArrayList<>();

            if (agentNames != null) {
                for (int aIdx = 0; aIdx < agentNames.size(); aIdx++) {
                    tasks.add(Task.builder()
                            .order(aIdx)
                            .taskId(UUID.randomUUID())
                            .description(agentNames.get(aIdx) + "：" + phase.getName())
                            .agentIndex(aIdx)
                            .agentName(agentNames.get(aIdx))
                            .status("pending")
                            .completed(false)
                            .build());
                }
                totalTasks += agentNames.size();
            }

            milestones.add(Milestone.builder()
                    .name(phase.getName() != null ? phase.getName() : "阶段 " + pIdx)
                    .description(phase.getDescription())
                    .order(pIdx)
                    .phaseIndex(pIdx)
                    .phaseMode(phase.getMode() != null ? phase.getMode().name() : "SEQUENTIAL")
                    .tasks(tasks)
                    .build());
        }

        Goal goal = Goal.builder()
                .goalId(UUID.randomUUID())
                .accountId(accountId)
                .projectId(projectId)
                .conversationId(conversationId)
                .title(squad.getName() != null ? squad.getName() : "自动编排")
                .description(taskDescription)
                .status(GoalStatus.ACTIVE)
                .autoMode(AutoMode.AUTO)
                .milestones(milestones)
                .totalTasks(totalTasks)
                .completedTasks(0)
                .currentMilestone(0)
                .currentPhaseIndex(0)
                .sourceSquadId(squad.getSquadId())
                .squadTopologyJson(topologyJson)
                .fileRootPath(fileRootPath)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        goalRepository.save(goal);
        return goal;
    }

    /**
     * @deprecated 由 {@link #createGoalBlueprint} 替代。原始实现仅在执行后记录，新方法在执行前创建蓝图。
     */
    @Deprecated
    private static Goal buildGoalFromSquad(Squad squad, String taskDescription,
                                            UUID accountId, UUID conversationId) {
        List<Milestone> milestones = new ArrayList<>();
        List<PhaseNode> phases = squad.getPhases();
        if (phases != null) {
            for (int i = 0; i < phases.size(); i++) {
                PhaseNode phase = phases.get(i);
                List<Task> tasks = new ArrayList<>();
                List<String> agentNames = phase.getAgents();
                if (agentNames != null) {
                    for (int j = 0; j < agentNames.size(); j++) {
                        tasks.add(Task.builder()
                                .order(j)
                                .taskId(UUID.randomUUID())
                                .description(agentNames.get(j) + "：" + phase.getName())
                                .status("pending")
                                .completed(false)
                                .build());
                    }
                }
                milestones.add(Milestone.builder()
                        .name(phase.getName())
                        .description(phase.getDescription())
                        .order(i)
                        .tasks(tasks)
                        .build());
            }
        }
        int totalTasks = milestones.stream().mapToInt(m -> m.getTasks() != null ? m.getTasks().size() : 0).sum();
        return Goal.builder()
                .goalId(UUID.randomUUID())
                .accountId(accountId)
                .conversationId(conversationId)
                .title(squad.getName() != null ? squad.getName() : "自动编排")
                .description(taskDescription)
                .status(GoalStatus.ACTIVE)
                .autoMode(AutoMode.AUTO)
                .milestones(milestones)
                .currentMilestone(0)
                .totalTasks(totalTasks)
                .completedTasks(0)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private static List<String> extractAgentNames(Squad squad) {
        if (squad.getPhases() == null) return List.of();
        return squad.getPhases().stream()
                .filter(p -> p.getAgents() != null)
                .flatMap(p -> p.getAgents().stream())
                .distinct()
                .toList();
    }

    private static String toUserMessage(String fallbackPrefix, Throwable e) {
        if (e == null) return fallbackPrefix + "：未知错误";
        if (e instanceof DataIntegrityViolationException) {
            return fallbackPrefix + "：数据冲突，请稍后重试";
        }
        String msg = e.getMessage();
        if (msg == null) return fallbackPrefix + "：未知错误";
        if (msg.contains("could not execute batch") || msg.contains("Batch entry")
                || msg.contains("could not execute statement") || msg.contains("SQL")
                || msg.contains("constraint") || msg.contains("violation")) {
            return fallbackPrefix + "：数据异常，请稍后重试";
        }
        if (msg.contains("Connection refused") || msg.contains("connect timed out")
                || msg.contains("Failed to connect") || msg.contains("Host")
                || e instanceof java.net.SocketTimeoutException
                || e instanceof java.net.ConnectException) {
            return fallbackPrefix + "：服务暂时不可用，请稍后重试";
        }
        if (msg.contains("401") || msg.contains("403") || msg.contains("Unauthorized")
                || msg.contains("Forbidden") || msg.contains("API key")
                || msg.contains("api_key") || msg.contains("Bearer")) {
            return fallbackPrefix + "：服务认证失败，请检查配置";
        }
        return fallbackPrefix + "：服务异常，请稍后重试";
    }

    private static String sanitizeSseContent(String content) {
        return com.icusu.sivan.infra.shared.sse.SseSanitizer.sanitize(content);
    }

    static String truncateStr(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    private void recordExecutionOutcome(SquadExecution execution,
                                        PatternFeedbackRecord.FeedbackOutcome outcome,
                                        String errorMessage,
                                        Squad squad) {
        try {
            PatternFeedbackRecord record = PatternFeedbackRecord.builder()
                    .accountId(execution.getAccountId())
                    .executionId(execution.getExecutionId())
                    .taskDescription(execution.getTaskDescription())
                    .outcome(outcome)
                    .outcomeReason(outcome == PatternFeedbackRecord.FeedbackOutcome.FAILURE
                            ? truncateStr(errorMessage, 200) : null)
                    .source(PatternFeedbackRecord.FeedbackSource.TRIGGER_LLM.name())
                    .build();
            patternFeedbackRepository.save(record);
            log.debug("记录执行结果反馈: execId={}, outcome={}", execution.getExecutionId(), outcome);

            if (outcome == PatternFeedbackRecord.FeedbackOutcome.SUCCESS && squad != null) {
                String topologyJson = buildTopologySnapshot(squad);
                instinctPatternService.processFeedback(record, topologyJson);
            }
        } catch (Exception e) {
            log.warn("记录执行结果反馈失败: execId={}", execution.getExecutionId(), e);
        }
    }
}
