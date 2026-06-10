package com.icusu.sivan.agent.forest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.icusu.sivan.agent.model.DefaultModelRouter;
import com.icusu.sivan.core.message.Content;
import com.icusu.sivan.core.message.Msg;
import com.icusu.sivan.core.message.Role;
import com.icusu.sivan.core.model.Model;
import com.icusu.sivan.core.tool.ToolRegistry;
import com.icusu.sivan.core.tool.ToolSpec;
import com.icusu.sivan.core.model.TokenUsage;
import com.icusu.sivan.domain.tool.IToolUsageRepository;
import com.icusu.sivan.domain.tool.ToolUsage;
import com.icusu.sivan.infra.forest.entity.ForestAgentMessageEntity;
import com.icusu.sivan.infra.forest.repository.ForestAgentMessageJpaRepository;
import com.icusu.sivan.domain.forest.ForestEvent;
import com.icusu.sivan.domain.forest.context.ExecutionContext;
import com.icusu.sivan.domain.forest.service.AgentMessage;
import com.icusu.sivan.domain.forest.service.AgentMessageBus;
import com.icusu.sivan.domain.forest.service.EventSink;
import com.icusu.sivan.domain.forest.service.LeafExecutor;
import com.icusu.sivan.domain.forest.tree.ContentNode;
import com.icusu.sivan.domain.forest.tree.TreeNode;
import com.icusu.sivan.infra.forest.execution.ForestExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Agent 叶子执行器 — 基于 ReAct 循环 + MCP 工具调用的 TaskNode 执行器。
 * <p>
 * 支持 Agent-to-Agent 通信：通过 {@link AgentMessageBus} 接收和发送消息。
 */
@Component
public class AgentLeafExecutor implements LeafExecutor {

    private static final Logger log = LoggerFactory.getLogger(AgentLeafExecutor.class);

    private static final int MAX_TOOL_OUTPUT_CHARS = 3000;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Value("${sivan.agent.max-rounds:200}")
    private int maxRounds;

    private final DefaultModelRouter modelRouter;
    private final ToolRegistry toolRegistry;
    private final IToolUsageRepository toolUsageRepository;
    private final ForestAgentMessageJpaRepository a2aMessageRepository;

    public AgentLeafExecutor(DefaultModelRouter modelRouter, ToolRegistry toolRegistry,
                             IToolUsageRepository toolUsageRepository,
                             ForestAgentMessageJpaRepository a2aMessageRepository) {
        this.modelRouter = modelRouter;
        this.toolRegistry = toolRegistry;
        this.toolUsageRepository = toolUsageRepository;
        this.a2aMessageRepository = a2aMessageRepository;
    }

    @Override
    public String supportedType() {
        return "task";
    }

    @Override
    public Flux<ForestEvent> execute(TreeNode node, ExecutionContext ctx, EventSink sink) {
        UUID accountId = ctx.accountId();
        String taskContent = node instanceof ContentNode c ? c.content() : "";

        Model model;
        try {
            model = modelRouter.getDefaultModel(accountId);
        } catch (Exception e) {
            log.error("[Agent] 获取默认模型失败: {}", e.getMessage());
            return Flux.just(ForestEvent.error(node.nodeId(), null, accountId.toString(),
                    "获取模型失败: " + e.getMessage()));
        }

        // 获取工具列表：优先使用元数据中指定的
        List<ToolSpec> tools;
        if (node instanceof ContentNode cn) {
            Object raw = cn.metadata().get("preferredToolSpecs");
            if (raw instanceof List<?> list && !list.isEmpty()) {
                tools = list.stream()
                        .filter(ToolSpec.class::isInstance)
                        .map(ToolSpec.class::cast)
                        .toList();
            } else {
                tools = toolRegistry.allSpecs();
            }
        } else {
            tools = toolRegistry.allSpecs();
        }

        // 注册 A2A 通信工具（始终可用）
        List<ToolSpec> allTools = new ArrayList<>(tools);
        allTools.add(buildA2AToolSpec());

        // 获取 A2A 消息总线 and forestId（用于持久化）
        AgentMessageBus bus = ForestExecutor.activeBus();
        ConcurrentLinkedQueue<AgentMessage> pendingMessages = new ConcurrentLinkedQueue<>();
        String agentId = node.nodeId();
        UUID forestId = null;
        if (node instanceof ContentNode cn) {
            Object raw = cn.metadata().get("_forestId");
            if (raw instanceof String s && !s.isEmpty()) {
                try { forestId = UUID.fromString(s); } catch (Exception ignored) {}
            }
        }

        // 订阅 A2A 消息
        bus.subscribe(agentId).subscribe(pendingMessages::add);
        // 订阅广播
        bus.subscribe("broadcast").subscribe(pendingMessages::add);

        log.info("[Agent] 执行任务: nodeId={} content={} 可用工具={} A2A={}",
                agentId, taskContent, allTools.size(), bus.activeTopics().size());

        UUID convId = ctx.conversationId();

        // 构建消息列表
        List<Msg> messages = buildMessages(node, taskContent, pendingMessages);

        return reactLoop(model, messages, allTools, node, accountId, 0, pendingMessages, bus, agentId, convId, forestId)
                .doOnComplete(() -> log.info("[Agent] 完成: nodeId={}", agentId))
                .onErrorResume(e -> {
                    log.error("[Agent] 执行异常: {}", e.getMessage(), e);
                    return Flux.just(ForestEvent.error(node.nodeId(), null, accountId.toString(),
                            "执行异常: " + e.getMessage()));
                });
    }

    @Override
    public int maxRetries() {
        return 1;
    }

    // =====================================================================
    // A2A 工具
    // =====================================================================

    private ToolSpec buildA2AToolSpec() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new HashMap<>();
        props.put("targetAgentId", Map.of("type", "string", "description", "目标 Agent 的 nodeId"));
        props.put("content", Map.of("type", "string", "description", "消息内容"));
        props.put("type", Map.of("type", "string", "description", "消息类型：REQUEST / RESPONSE / BROADCAST（默认 REQUEST）"));
        schema.put("properties", props);
        schema.put("required", List.of("targetAgentId", "content"));

        return new ToolSpec("send_agent_message",
                "向同一任务中的其他 Agent 发送消息，用于协作、请求数据或委托子任务。"
                        + "targetAgentId 为对方的 nodeId，可通过 A2A 消息中的 sourceAgentId 获取。",
                schema);
    }

    // =====================================================================
    // 消息构建
    // =====================================================================

    private List<Msg> buildMessages(TreeNode node, String taskContent,
                                     ConcurrentLinkedQueue<AgentMessage> pendingMessages) {
        List<Msg> messages = null;
        if (node instanceof ContentNode cn) {
            Object raw = cn.metadata().get("prebuiltMessages");
            if (raw instanceof List<?> list) {
                messages = list.stream()
                        .filter(Msg.class::isInstance)
                        .map(Msg.class::cast)
                        .toList();
            }
        }
        if (messages == null || messages.isEmpty()) {
            messages = new ArrayList<>();
            messages.add(Msg.of(Role.SYSTEM, List.of(new Content.Text(
                    "你是一个 AI 助手，请根据用户请求完成任务。你可以使用以下工具来帮助你完成任务。"
                            + "\n\n你可以使用 send_agent_message 工具与同一任务中的其他 Agent 协作。"))));
            String accumulatedContext = node instanceof ContentNode cn2
                    ? (String) cn2.metadata().get("accumulatedContext") : null;
            if (accumulatedContext != null && !accumulatedContext.isEmpty()) {
                messages.add(Msg.of(Role.USER, List.of(new Content.Text(
                        "以下是从之前步骤获取的结果，你可以在本次任务中参考这些信息：\n" + accumulatedContext))));
            }
            messages.add(Msg.of(Role.USER, List.of(new Content.Text(taskContent))));
        }

        // 注入待处理的 A2A 消息
        injectPendingA2AMessages(messages, pendingMessages);
        return messages;
    }

    private void injectPendingA2AMessages(List<Msg> messages, ConcurrentLinkedQueue<AgentMessage> pending) {
        AgentMessage msg;
        while ((msg = pending.poll()) != null) {
            String prefix = switch (msg.type()) {
                case REQUEST -> "[来自 " + msg.sourceAgentId() + " 的请求]";
                case RESPONSE -> "[来自 " + msg.sourceAgentId() + " 的回复]";
                case BROADCAST -> "[广播消息]";
                case DELEGATE -> "[来自 " + msg.sourceAgentId() + " 的委托]";
            };
            messages.add(Msg.of(Role.USER, List.of(new Content.Text(
                    prefix + "\n" + msg.content()))));
        }
    }

    // =====================================================================
    // ReAct 循环
    // =====================================================================

    private Flux<ForestEvent> reactLoop(Model model, List<Msg> messages, List<ToolSpec> tools,
                                         TreeNode node, UUID accountId, int round,
                                         ConcurrentLinkedQueue<AgentMessage> pendingMessages,
                                         AgentMessageBus bus, String agentId, UUID convId, UUID forestId) {
        if (round >= maxRounds) {
            log.warn("[Agent] 达到最大轮数: {}", maxRounds);
            return Flux.empty();
        }

        return Flux.defer(() -> {
            StringBuilder textAcc = new StringBuilder();
            StringBuilder thinkAcc = new StringBuilder();
            Map<Integer, ToolCallAcc> toolAccs = new HashMap<>();
            final TokenUsage[] lastTokenUsage = {null};

            return model.stream(messages, tools, Model.ModelParams.defaults())
                    .concatMap(chunk -> {
                        if (chunk.usage() != null) lastTokenUsage[0] = chunk.usage();
                        List<ForestEvent> events = new ArrayList<>(2);
                        if (!chunk.content().isEmpty()) {
                            textAcc.append(chunk.content());
                            events.add(ForestEvent.detail(node.nodeId(), null,
                                    accountId.toString(), chunk.content()));
                        }
                        if (!chunk.thinking().isEmpty()) {
                            thinkAcc.append(chunk.thinking());
                            events.add(ForestEvent.thinking(node.nodeId(), null,
                                    accountId.toString(), chunk.thinking()));
                        }
                        for (var d : chunk.toolCallDeltas()) {
                            var acc = toolAccs.computeIfAbsent(d.index(), k -> new ToolCallAcc());
                            if (d.id() != null) acc.id = d.id();
                            if (d.name() != null) acc.name = d.name();
                            if (d.arguments() != null) acc.args.append(d.arguments());
                        }
                        return Flux.fromIterable(events);
                    })
                    .concatWith(Flux.defer(() -> {
                        List<Content> contents = new ArrayList<>();
                        if (!thinkAcc.isEmpty()) {
                            contents.add(new Content.Thinking(thinkAcc.toString(), ""));
                        }
                        if (!textAcc.isEmpty()) {
                            contents.add(new Content.Text(textAcc.toString()));
                        }
                        for (ToolCallAcc acc : toolAccs.values()) {
                            contents.add(new Content.ToolCall(
                                    acc.id != null ? acc.id : "",
                                    acc.name != null ? acc.name : "",
                                    parseToolArgs(acc.args.toString())));
                        }

                        List<Msg> newMessages = new ArrayList<>(messages.size() + 1);
                        newMessages.addAll(messages);
                        newMessages.add(Msg.of(Role.ASSISTANT, contents));

                        List<Content.ToolCall> toolCalls = contents.stream()
                                .filter(c -> c instanceof Content.ToolCall)
                                .map(c -> (Content.ToolCall) c)
                                .toList();

                        if (toolCalls.isEmpty()) {
                            // 本轮完成，发射 token 用量
                            int totalTokens = lastTokenUsage[0] != null
                                    ? lastTokenUsage[0].totalTokens() : 0;
                            int thinkingTokens = lastTokenUsage[0] != null
                                    ? lastTokenUsage[0].thinkingTokens() : 0;
                            ForestEvent tokenEvent = new ForestEvent(node.nodeId(), null,
                                    accountId.toString(), ForestEvent.EventType.MILESTONE,
                                    "{\"totalTokens\":" + totalTokens
                                    + ",\"thinkingTokens\":" + thinkingTokens + "}");
                            return Flux.just(tokenEvent);
                        }

                        log.info("[Agent] 工具调用: {}", toolCalls.stream()
                                .map(Content.ToolCall::name).toList());

                        // 先检查 A2A 消息，再处理其他工具
                        List<Content.ToolCall> a2aCalls = toolCalls.stream()
                                .filter(tc -> "send_agent_message".equals(tc.name()))
                                .toList();
                        List<Content.ToolCall> otherCalls = toolCalls.stream()
                                .filter(tc -> !"send_agent_message".equals(tc.name()))
                                .toList();

                        // 处理 A2A 消息：发布到总线
                        for (Content.ToolCall a2aCall : a2aCalls) {
                            Map<String, Object> args = a2aCall.args() != null ? a2aCall.args() : Map.of();
                            String targetId = (String) args.get("targetAgentId");
                            String content = (String) args.get("content");
                            String typeStr = (String) args.get("type");
                            AgentMessage.MessageType msgType = "BROADCAST".equals(typeStr)
                                    ? AgentMessage.MessageType.BROADCAST
                                    : AgentMessage.MessageType.REQUEST;
                            // 如果内容是 RESPONSE 格式，自动转为 RESPONSE 类型
                            if ("RESPONSE".equals(typeStr)) msgType = AgentMessage.MessageType.RESPONSE;
                            AgentMessage a2aMsg = new AgentMessage(agentId, targetId,
                                    "RESPONSE".equals(typeStr) ? targetId : targetId,
                                    content != null ? content : "", msgType);
                            bus.publish(a2aMsg);
                            persistA2AMessage(forestId, node.nodeId(), agentId, targetId,
                                    targetId, content, msgType.name());
                            log.info("[A2A] {} → {}: {}", agentId.substring(0, 8),
                                    targetId.length() > 8 ? targetId.substring(0, 8) : targetId,
                                    truncateStr(content, 50));
                        }

                        // 注入本轮收到的 A2A 消息
                        injectPendingA2AMessages(newMessages, pendingMessages);

                        if (!otherCalls.isEmpty()) {
                            return handleToolCalls(model, newMessages, tools, node, accountId, round, otherCalls,
                                    pendingMessages, bus, agentId, convId, forestId);
                        }
                        // 仅 A2A 消息，无其他工具 → 继续下一轮
                        return reactLoop(model, newMessages, allToolsWithA2A(tools), node, accountId, round + 1,
                                pendingMessages, bus, agentId, convId, forestId);
                    }));
        });
    }

    private static List<ToolSpec> allToolsWithA2A(List<ToolSpec> tools) {
        List<ToolSpec> result = new ArrayList<>(tools);
        if (result.stream().noneMatch(t -> "send_agent_message".equals(t.name()))) {
            // A2A 工具已在 allTools 中注册，此处仅确保不丢失
        }
        return result;
    }

    // =====================================================================
    // 工具调用
    // =====================================================================

    private Flux<ForestEvent> handleToolCalls(Model model, List<Msg> messages, List<ToolSpec> tools,
                                               TreeNode node, UUID accountId, int round,
                                               List<Content.ToolCall> toolCalls,
                                               ConcurrentLinkedQueue<AgentMessage> pendingMessages,
                                               AgentMessageBus bus, String agentId, UUID convId, UUID forestId) {
        Flux<ForestEvent> callEvents = Flux.fromIterable(toolCalls)
                .map(tc -> ForestEvent.toolCall(node.nodeId(), null, accountId.toString(),
                        json("name", tc.name(), "args", toJsonString(tc.args()), "id", tc.id())));

        List<Mono<ToolCallResult>> monos = toolCalls.stream()
                .map(tc -> executeSingleTool(tc, accountId, node, convId))
                .toList();

        Flux<ForestEvent> resultEvents = Flux.mergeSequential(monos)
                .flatMap(r -> {
                    ForestEvent event = ForestEvent.toolResult(node.nodeId(), null, accountId.toString(),
                            json("name", r.name(), "success", String.valueOf(r.success()),
                                    "output", r.output()));
                    return Mono.just(event);
                })
                .concatWith(Flux.defer(() ->
                    collectResults(monos).flatMapMany(results -> {
                        List<Msg> newMessages = new ArrayList<>(messages);
                        for (var r : results) {
                            newMessages.add(Msg.of(Role.TOOL,
                                    List.of(new Content.ToolResult(r.id(), r.success(), r.content()))));
                        }
                        injectPendingA2AMessages(newMessages, pendingMessages);
                        return reactLoop(model, newMessages, tools, node, accountId, round + 1,
                                pendingMessages, bus, agentId, convId, forestId);
                    })
                ));

        return Flux.concat(callEvents, resultEvents);
    }

    private Mono<ToolCallResult> executeSingleTool(Content.ToolCall tc, UUID acctId, TreeNode toolNode, UUID convId) {
        long toolStartMs = System.currentTimeMillis();
        var executor = toolRegistry.find(tc.name());
        if (executor == null) {
            recordToolUsage(tc.name(), acctId, toolNode, false, 0, convId);
            return Mono.just(new ToolCallResult(tc.id(), tc.name(), false, "工具未注册: " + tc.name()));
        }
        // 为内部文件/命令工具注入项目路径参数
        Content.ToolCall enhancedTc = tc;
        String tcName = tc.name();
        if (tcName.startsWith("file_") || "bash".equals(tcName)) {
            Map<String, Object> mergedArgs = new HashMap<>(tc.args() != null ? tc.args() : Map.of());
            if (toolNode instanceof ContentNode cn) {
                Object frp = cn.metadata().get("_fileRootPath");
                if (frp instanceof String s && !s.isEmpty()) mergedArgs.put("_fileRootPath", s);
                Object archived = cn.metadata().get("_archived");
                if (archived instanceof Boolean b) mergedArgs.put("_archived", b);
            }
            enhancedTc = new Content.ToolCall(tc.id(), tcName, mergedArgs);
        }
        Map<String, Object> attrs = Map.of("_accountId", acctId.toString());
        var toolCtx = com.icusu.sivan.core.context.ExecutionContext.create(null, List.of(), attrs);
        return executor.execute(enhancedTc, toolCtx)
                .map(r -> new ToolCallResult(tc.id(), tc.name(), r.success(),
                        truncateOutput(r.output())))
                .doOnNext(r -> recordToolUsage(tc.name(), acctId, toolNode, r.success(),
                        (int) (System.currentTimeMillis() - toolStartMs), convId))
                .onErrorResume(e -> {
                    log.warn("[Agent] 工具执行失败: {} error={}", tc.name(), e.getMessage());
                    recordToolUsage(tc.name(), acctId, toolNode, false,
                            (int) (System.currentTimeMillis() - toolStartMs), convId);
                    return Mono.just(new ToolCallResult(tc.id(), tc.name(), false, e.getMessage()));
                });
    }

    private Mono<List<Content.ToolResult>> collectResults(List<Mono<ToolCallResult>> monos) {
        return Flux.mergeSequential(monos)
                .map(r -> new Content.ToolResult(r.id(), r.success(), r.output()))
                .collectList();
    }

    // =====================================================================
    // 内部类型 & 工具方法
    // =====================================================================

    private record ToolCallResult(String id, String name, boolean success, String output) {}

    private static class ToolCallAcc {
        String id;
        String name;
        final StringBuilder args = new StringBuilder();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseToolArgs(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try { return MAPPER.readValue(json, Map.class); }
        catch (Exception e) { return Map.of(); }
    }

    private void persistA2AMessage(UUID forestId, String scopeNodeId, String sourceAgent,
                                    String targetAgent, String topic, String content, String msgType) {
        if (forestId == null) return;
        try {
            a2aMessageRepository.save(ForestAgentMessageEntity.builder()
                    .forestId(forestId)
                    .scopeNodeId(scopeNodeId)
                    .sourceAgent(sourceAgent)
                    .targetAgent(targetAgent)
                    .topic(topic)
                    .messageType(msgType)
                    .payload(content)
                    .build());
        } catch (Exception e) {
            log.debug("A2A 消息持久化失败（不影响执行）: {}", e.getMessage());
        }
    }

    private void recordToolUsage(String toolName, UUID acctId, TreeNode toolNode,
                                  boolean success, int durationMs, UUID convId) {
        try {
            toolUsageRepository.save(ToolUsage.builder()
                    .accountId(acctId)
                    .agentName(toolNode != null ? toolNode.nodeId() : null)
                    .toolName(toolName)
                    .serverId("")
                    .success(success)
                    .durationMs(durationMs)
                    .conversationId(convId)
                    .build());
        } catch (Exception e) {
            log.debug("工具使用记录失败（不影响执行）: {}", e.getMessage());
        }
    }

    private static String truncateOutput(String output) {
        if (output == null || output.length() <= MAX_TOOL_OUTPUT_CHARS) return output;
        return output.substring(0, MAX_TOOL_OUTPUT_CHARS)
                + "\n\n[... 截断: 原始输出 " + output.length() + " 字符]";
    }

    private static String json(String... pairs) {
        try {
            var map = new java.util.LinkedHashMap<String, String>();
            for (int i = 0; i < pairs.length; i += 2) {
                if (i + 1 < pairs.length) map.put(pairs[i], pairs[i + 1]);
            }
            return MAPPER.writeValueAsString(map);
        } catch (Exception e) { return "{}"; }
    }

    private static String toJsonString(Object obj) {
        try { return MAPPER.writeValueAsString(obj); }
        catch (Exception e) { return "{}"; }
    }

    private static String truncateStr(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
