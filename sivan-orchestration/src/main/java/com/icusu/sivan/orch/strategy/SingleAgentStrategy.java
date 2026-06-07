package com.icusu.sivan.orch.strategy;

import com.icusu.sivan.orch.executor.OrchestrationEvent;
import com.icusu.sivan.orch.topology.AgentAutoCreator;
import com.icusu.sivan.orch.topology.TopologyGenerator;
import com.icusu.sivan.agent.model.ModelRouter;
import com.icusu.sivan.agent.prompt.AgentPrompts;
import com.icusu.sivan.agent.routing.RoutingDecisionRecorder;
import com.icusu.sivan.agent.routing.RoutingDecisionRecorder.RecordRequest;
import com.icusu.sivan.agent.routing.RoutingEngine;
import com.icusu.sivan.agent.service.TokenUsageRecorder;
import com.icusu.sivan.agent.strategy.ReActExecutionStrategy;
import com.icusu.sivan.agent.tool.MatchedTools;
import com.icusu.sivan.agent.tool.ToolEnricher;
import com.icusu.sivan.agent.tool.ToolResolver;
import com.icusu.sivan.common.enums.Intent;
import com.icusu.sivan.common.enums.TokenSource;
import com.icusu.sivan.core.agent.Agent;
import com.icusu.sivan.core.agent.AgentEvent;
import com.icusu.sivan.core.agent.ExecutionStrategy;
import com.icusu.sivan.core.context.ExecutionContext;
import com.icusu.sivan.core.message.Content;
import com.icusu.sivan.core.message.Msg;
import com.icusu.sivan.core.message.Role;
import com.icusu.sivan.core.model.Model;
import com.icusu.sivan.core.tool.ToolProvider;
import com.icusu.sivan.core.tool.ToolSpec;
import com.icusu.sivan.domain.agent.AgentDefinition;
import com.icusu.sivan.domain.agent.IAgentRepository;
import com.icusu.sivan.domain.model.LlmProvider;
import com.icusu.sivan.domain.shared.vo.TokenContext;
import com.icusu.sivan.orch.topology.SkillAutoCreator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 单智能体路由策略：匹配 Agent → 无匹配时自动创建 → Agent 执行（通过 {@link Agent} 中心入口）。
 * <p>
 * LLM 调用 + 工具循环由 {@link ExecutionStrategy}（ReActExecutionStrategy）处理，
 * 本策略只负责 Agent 匹配/创建和事件转换。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SingleAgentStrategy implements OrchestrationStrategy {

    private final IAgentRepository agentRepository;
    private final RoutingDecisionRecorder routingDecisionRecorder;
    private final TopologyGenerator topologyGenerator;
    private final AgentAutoCreator agentAutoCreator;
    private final SkillAutoCreator skillAutoCreator;
    private final ToolResolver toolAutoResolver;
    private final ExecutionStrategy executionStrategy;
    private final ModelRouter modelRouter;
    private final ToolProvider toolProvider;
    private final RoutingEngine routingEngine;
    private final ToolEnricher toolEnricher;
    private final TokenUsageRecorder tokenUsageRecorder;
    private final com.icusu.sivan.domain.agent.ISkillRepository skillRepository;

    @Override
    public Intent supportedIntent() {
        return Intent.SINGLE_AGENT;
    }

    @Override
    public Flux<OrchestrationEvent> execute(OrchestrationContext ctx) {
        return Flux.concat(
                Flux.just(OrchestrationEvent.stepStart("match_agent", "正在匹配专业智能体...")),
                Flux.defer(() -> resolveAndExecute(ctx))
        ).onErrorResume(e -> {
            log.error("单智能体路由异常: task={}", truncate(ctx.taskDescription()), e);
            return Flux.just(OrchestrationEvent.error(e.getMessage()));
        });
    }

    /**
     * 解析智能体（显式指定 / 路由匹配 / 自动创建），然后执行。
     * 全响应式链，零阻塞。
     */
    private Flux<OrchestrationEvent> resolveAndExecute(OrchestrationContext ctx) {
        // 1. 检查显式指定的智能体
        if (ctx.targetAgent() != null) {
            var existing = agentRepository.findByAccountAndName(ctx.accountId(), ctx.targetAgent());
            if (existing.isPresent()) {
                log.info("使用显式指定的智能体: {}", ctx.targetAgent());
                return Flux.concat(
                        Flux.just(OrchestrationEvent.stepEnd("match_agent",
                                "已匹配智能体「" + ctx.targetAgent() + "」")),
                        doExecute(ctx.targetAgent(), ctx)
                );
            }
            log.info("显式指定智能体不存在，自动创建: {}", ctx.targetAgent());
        }

        // 2. 路由匹配（全响应式）
        return routingEngine.resolve(ctx.taskDescription(), ctx.accountId(), ctx.conversationId())
                .flatMapMany(name -> {
                    if (name != null) {
                        return Flux.concat(Flux.just(OrchestrationEvent.stepEnd("match_agent", "已匹配智能体「" + name + "」")), doExecute(name, ctx));
                    }
                    return createAndExecute(ctx);
                })
                .switchIfEmpty(Flux.defer(() -> createAndExecute(ctx)))
                .onErrorResume(e -> {
                    log.warn("路由决策异常，尝试自动创建智能体: {}", e.getMessage());
                    return createAndExecute(ctx);
                });
    }

    /**
     * 自动创建智能体并执行。
     * inferSquadMeta → createAgent → createForAgent → execute，全响应式链。
     */
    private Flux<OrchestrationEvent> createAndExecute(OrchestrationContext ctx) {
        Flux<OrchestrationEvent> createStart = Flux.just(
                OrchestrationEvent.stepStart("create_agent", "未匹配到现有智能体，正在自动创建...")
        );

        UUID projectId = ctx.account().projectId();

        // 全响应式创建链：推断元信息 → 创建 Agent → 创建技能
        Mono<AgentDefinition> createChain = topologyGenerator.inferSquadMeta(ctx.accountId(), ctx.taskDescription(), ctx.historyContext())
                .flatMap(meta -> agentAutoCreator.create(meta.name(), meta.taskType(), meta.taskType(),
                        ctx.accountId(), projectId))
                .flatMap(agent -> skillAutoCreator.createForAgent(agent, ctx.accountId(), projectId)
                        .thenReturn(agent));

        // 创建完成后：持久化 + 记录路由决策 → 执行 Agent
        Flux<OrchestrationEvent> createEndAndExec = createChain
                .flatMapMany(agent -> {
                    agentRepository.save(agent);
                    routingDecisionRecorder.record(RecordRequest.simple(
                            ctx.accountId(), projectId, ctx.conversationId(), ctx.taskDescription(),
                            "AUTO_CREATE", agent.getAgentName(), true,
                            "自动创建智能体: " + agent.getAgentName(), null));
                    log.info("自动创建智能体成功: agentName={}", agent.getAgentName());

                    return Flux.concat(
                            Flux.just(
                                    OrchestrationEvent.stepEnd("create_agent",
                                            "智能体「" + agent.getAgentName() + "」创建完成"),
                                    OrchestrationEvent.stepEnd("match_agent",
                                            "已匹配智能体「" + agent.getAgentName() + "」")
                            ),
                            doExecute(agent.getAgentName(), ctx)
                    );
                })
                .onErrorResume(e -> {
                    log.error("自动创建智能体失败，回退到聊天", e);
                    routingDecisionRecorder.record(RecordRequest.simple(
                            ctx.accountId(), projectId, ctx.conversationId(), ctx.taskDescription(),
                            "AUTO_CREATE", "CHAT_FALLBACK", false,
                            "自动创建智能体失败，降级为 CHAT: " + e.getMessage(), null));
                    return Flux.just(OrchestrationEvent.complete(Map.of("type", "chat")));
                });

        return Flux.concat(createStart, createEndAndExec);
    }

    /**
     * 构建并执行 Agent，将 AgentEvent 流映射为 OrchestrationEvent 流。
     */
    private Flux<OrchestrationEvent> doExecute(String agentName, OrchestrationContext ctx) {
        UUID projectId = ctx.account().projectId();

        // 查询智能体配置 + 构建提示词
        var agentConfig = agentRepository.findByAccountAndName(
                ctx.accountId(), agentName);
        String systemPrompt = agentConfig.map(AgentDefinition::getSystemPrompt)
                .filter(p -> !p.isBlank())
                .orElse(AgentPrompts.agentFallbackSystem(agentName).content());

        String userContent = AgentPrompts.singleAgentUser(ctx.historyContext(), ctx.taskDescription()).content();

        // 执行阶段事件：step_start 前置，step_end 在执行完成后（handleAgentEvent 中）发出以携带实际 token 数据
        Flux<OrchestrationEvent> setupEvents = Flux.just(
                OrchestrationEvent.stepStart("execute_agent",
                        "正在调用智能体「" + agentName + "」处理您的请求...",
                        Map.of("agentName", agentName))
        );

        // 工具决议：MCP 工具 + 智能体语义匹配工具合并
        List<ToolSpec> toolSpecs = new ArrayList<>();
        if (ctx.mcpTools() != null) {
            toolSpecs.addAll(ctx.mcpTools());
        }
        MatchedTools matched = toolAutoResolver.resolveForAgent(agentName, ctx.accountId());
        if (matched != null && !matched.isEmpty()) {
            toolSpecs.addAll(toolEnricher.toSchemas(matched));
        }
        if (toolSpecs.size() > 1) {
            toolSpecs = toolSpecs.stream()
                    .collect(Collectors.toMap(
                            ToolSpec::name, java.util.function.Function.identity(), (a, b) -> a))
                    .values().stream().toList();
        }
        // 构建 Agent（核心入口）
        Agent agent = Agent.builder()
                .agentId(agentName)
                .languageModel(resolveModel(ctx))
                .toolProvider(toolProvider)
                .skillProvider(new com.icusu.sivan.infra.skill.DbSkillProvider(ctx.accountId(), skillRepository))
                .executionStrategy(executionStrategy)
                .build();

        // 构建输入消息：优先复用 ConversationService 预构建的富化消息（含 UserProfile/Flashback/Goal/FileSnapshot）
        List<Msg> msgs;
        if (ctx.chatMsgs() != null && !ctx.chatMsgs().isEmpty()
                && ctx.chatMsgs().get(0).role() == Role.SYSTEM) {
            msgs = new ArrayList<>(ctx.chatMsgs());
            // 合并智能体匹配的工具定义到 SYSTEM 消息，不重复添加 projectHint（已在预构建消息中）
            if (!toolSpecs.isEmpty() && matched != null && !matched.isEmpty()) {
                String toolText = toolEnricher.enrichPrompt("", matched.metas());
                if (!toolText.isEmpty()) {
                    Msg sysMsg = msgs.get(0);
                    List<Content> newContents = new ArrayList<>(sysMsg.contents());
                    newContents.add(new Content.Text(toolText));
                    msgs.set(0, Msg.of(Role.SYSTEM, newContents));
                }
            }
        } else {
            // 无预构建消息时自行构建 [SYSTEM, USER] 消息对
            String enrichedPrompt = toolSpecs.isEmpty()
                    ? systemPrompt
                    : toolEnricher.enrichPrompt(systemPrompt, matched == null || matched.isEmpty() ? List.of() : matched.metas());
            if (ctx.projectHint() != null) {
                enrichedPrompt += "\n" + ctx.projectHint();
            }

            msgs = new ArrayList<>();
            msgs.add(Msg.of(Role.SYSTEM, List.of(new Content.Text(enrichedPrompt))));

            // 构建用户消息（含图片）：从 chatMsgs 最后一条用户消息中提取图片内容
            List<Content> userContents = new ArrayList<>();
            userContents.add(new Content.Text(userContent));
            if (ctx.chatMsgs() != null) {
                for (int i = ctx.chatMsgs().size() - 1; i >= 0; i--) {
                    Msg m = ctx.chatMsgs().get(i);
                    if (m.role() == Role.USER) {
                        for (Content c : m.contents()) {
                            if (c instanceof Content.Image img) {
                                userContents.add(img);
                            }
                        }
                        break;
                    }
                }
            }
            msgs.add(Msg.of(Role.USER, userContents));
        }

        Model.ModelParams params = Model.ModelParams.defaults();
        try {
            LlmProvider provider = resolveProvider(ctx);
            if (provider.getTemperature() != null) params = params.withTemperature(provider.getTemperature());
            if (provider.getContextLength() != null) {
                // contextLength 用于预算管理（告知 ReAct 策略上下文窗口大小），不映射到 max_tokens
                params = params.withContextLength(provider.getContextLength());
            }
        } catch (Exception e) {
            log.debug("使用默认 ModelParams: {}", e.getMessage());
        }

        ExecutionContext execCtx = ExecutionContext.create(ctx.conversationId().toString(), msgs,
                Map.of(ReActExecutionStrategy.ATTR_STREAM, ctx.stream(),
                        ReActExecutionStrategy.ATTR_PARAMS, params,
                        "_fileRootPath", ctx.fileRootPath() != null ? ctx.fileRootPath() : "",
                        "_archived", ctx.archived()));
        long startMs = System.currentTimeMillis();

        TokenContext tokenCtx = TokenContext.builder()
                .accountId(ctx.accountId())
                .projectId(projectId)
                .source(TokenSource.CHAT)
                .conversationId(ctx.conversationId())
                .agentId(agentConfig.map(AgentDefinition::getAgentId).orElse(null))
                .build();

        // 执行 Agent，将 AgentEvent 事件流映射为 OrchestrationEvent（全响应式）
        AtomicLong thinkingStartMs = new AtomicLong(0);
        AtomicLong thinkingEndMs = new AtomicLong(0);
        Flux<OrchestrationEvent> execEvents = agent.execute(execCtx)
                .concatMap(event -> handleAgentEvent(event, agentName, ctx, startMs, tokenCtx, thinkingStartMs, thinkingEndMs))
                .onErrorResume(e -> {
                    log.error("SINGLE_AGENT 执行异常: agentName={}", agentName, e);
                    return Flux.just(
                            OrchestrationEvent.stream("执行异常: " + e.getMessage()),
                            buildCompleteEvent(agentName, startMs, 0, 0, "", "", resolveModel(ctx).modelId(), new AtomicLong(0), new AtomicLong(0))
                    );
                });

        return Flux.concat(setupEvents, execEvents);
    }

    /**
     * 将 AgentEvent 映射为 OrchestrationEvent 流。
     */
    private Flux<OrchestrationEvent> handleAgentEvent(AgentEvent event, String agentName,
                                                      OrchestrationContext ctx, long startMs,
                                                      TokenContext tokenCtx, AtomicLong thinkingStartMs,
                                                      AtomicLong thinkingEndMs) {
        return switch (event) {
            case AgentEvent.Chunk c -> {
                if (thinkingStartMs.get() > 0 && thinkingEndMs.get() == 0) {
                    thinkingEndMs.compareAndSet(0, System.currentTimeMillis());
                }
                yield Flux.just(OrchestrationEvent.stream(c.delta()));
            }
            case AgentEvent.Thinking t -> {
                if (thinkingStartMs.get() == 0) {
                    thinkingStartMs.compareAndSet(0, System.currentTimeMillis());
                }
                yield Flux.just(OrchestrationEvent.streamThinking(t.content()));
            }
            case AgentEvent.ToolCall tc -> Flux.just(OrchestrationEvent.toolCall(tc.name(), tc.args()));
            case AgentEvent.ToolResult tr ->
                    Flux.just(OrchestrationEvent.toolResult(tr.name(), tr.success(), tr.output()));
            case AgentEvent.Completed c -> {
                var result = c.result();
                int totalTokens = result.usage() != null ? result.usage().totalTokens() : 0;
                String modelName = resolveModel(ctx).modelId();
                // Token 用量记录
                if (totalTokens > 0) {
                    tokenUsageRecorder.saveUsage(result.usage(), tokenCtx, modelName);
                }
                updateAgentUsage(agentName, ctx.accountId(), ctx.account().projectId());
                int thinkingTokens = result.usage() != null ? result.usage().thinkingTokens() : 0;
                Map<String, Object> stepMeta = new HashMap<>();
                stepMeta.put("tokens", totalTokens);
                stepMeta.put("model", modelName);
                stepMeta.put("agentName", agentName);
                yield Flux.just(
                        OrchestrationEvent.stepEnd("execute_agent", "智能体完成", stepMeta),
                        buildCompleteEvent(agentName, startMs, totalTokens, thinkingTokens,
                                result.content(), result.thinking(), modelName, thinkingStartMs, thinkingEndMs));
            }
            case AgentEvent.Error e -> {
                String modelName = resolveModel(ctx).modelId();
                Map<String, Object> stepMeta = new HashMap<>();
                stepMeta.put("agentName", agentName);
                stepMeta.put("model", modelName);
                yield Flux.just(
                        OrchestrationEvent.stepEnd("execute_agent", "执行异常", stepMeta),
                        OrchestrationEvent.error("执行异常: " + (e.cause() != null ? e.cause().getMessage() : "未知错误")),
                        buildCompleteEvent(agentName, startMs, 0, 0, "", "", modelName, thinkingStartMs, thinkingEndMs));
            }
        };
    }

    // ========== 完成事件 ==========

    private OrchestrationEvent buildCompleteEvent(String agentName, long startMs,
                                                  int totalTokens, int thinkingTokens, String content, String thinking,
                                                  String modelName, AtomicLong thinkingStartMs, AtomicLong thinkingEndMs) {
        long durationMs = System.currentTimeMillis() - startMs;
        long end = thinkingEndMs.get();
        long start = thinkingStartMs.get();
        int thinkingDurationMs = end > 0 && start > 0
                ? (int) (end - start)
                : 0;
        boolean hasContent = content != null && !content.isBlank();

        log.info("SINGLE_AGENT 完成: agentName={}, contentLen={}, totalTokens={}, thinkingTokens={}, thinkingDurationMs={}, durationMs={}",
                agentName, hasContent ? content.length() : 0, totalTokens, thinkingTokens, thinkingDurationMs, durationMs);

        Map<String, Object> meta = new HashMap<>();
        meta.put("type", "agent");
        meta.put("content", content);
        meta.put("thinking", thinking);
        meta.put("model", modelName);
        meta.put("tokens", totalTokens);
        meta.put("durationMs", durationMs);
        meta.put("agentName", agentName);
        meta.put("thinkingTokens", thinkingTokens);
        meta.put("thinkingDurationMs", thinkingDurationMs);
        return OrchestrationEvent.complete(meta);
    }

    private void updateAgentUsage(String agentName, UUID accountId, UUID projectId) {
        try {
            agentRepository.findByAccountAndName(accountId, agentName).ifPresent(agent -> {
                agent.recordUsage();
                agentRepository.save(agent);
            });
        } catch (Exception e) {
            log.warn("更新智能体使用计数失败: {}", e.getMessage());
        }
    }

    private static String truncate(String s) {
        return s != null && s.length() > 60 ? s.substring(0, 60) + "..." : s;
    }

    /** 解析模型：优先使用上下文中的 providerId，否则使用账户默认模型。 */
    private Model resolveModel(OrchestrationContext ctx) {
        if (ctx.providerId() != null) {
            return modelRouter.getModel(ctx.providerId());
        }
        return modelRouter.getDefaultModel(ctx.accountId());
    }

    /** 解析 LLM 提供商：优先使用上下文中的 providerId，否则使用账户默认提供商。 */
    private LlmProvider resolveProvider(OrchestrationContext ctx) {
        if (ctx.providerId() != null) {
            return modelRouter.getProvider(ctx.providerId());
        }
        return modelRouter.getDefaultProvider(ctx.accountId());
    }
}
