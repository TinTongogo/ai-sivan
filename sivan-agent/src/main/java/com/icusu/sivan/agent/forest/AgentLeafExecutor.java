package com.icusu.sivan.agent.forest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.icusu.sivan.agent.model.DefaultModelRouter;
import com.icusu.sivan.agent.routing.ForestNodeAdapter;
import com.icusu.sivan.agent.routing.ToolRouter;
import com.icusu.sivan.core.message.Content;
import com.icusu.sivan.core.message.Msg;
import com.icusu.sivan.core.message.Role;
import com.icusu.sivan.core.model.Model;
import com.icusu.sivan.core.model.TokenUsage;
import com.icusu.sivan.core.tool.ToolRegistry;
import com.icusu.sivan.core.tool.ToolSpec;
import com.icusu.sivan.domain.agent.IAgentRepository;
import com.icusu.sivan.domain.agent.ISkillRepository;
import com.icusu.sivan.domain.forest.ForestEvent;
import com.icusu.sivan.domain.forest.context.ExecutionContext;
import com.icusu.sivan.domain.forest.port.LeafExecutor;
import com.icusu.sivan.domain.forest.tree.ContentNode;
import com.icusu.sivan.domain.forest.tree.TreeNode;
import com.icusu.sivan.domain.forest.vo.AgentMessage;
import com.icusu.sivan.domain.forest.vo.AgentMessageBus;
import com.icusu.sivan.domain.shared.port.EventSink;
import com.icusu.sivan.domain.tool.IToolUsageRepository;
import com.icusu.sivan.domain.tool.ToolUsage;
import com.icusu.sivan.infra.forest.entity.ForestAgentMessageEntity;
import com.icusu.sivan.infra.forest.execution.ForestExecutor;
import com.icusu.sivan.infra.forest.repository.ForestAgentMessageJpaRepository;
import com.icusu.sivan.infra.prompt.PromptAssembler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;

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

    @Value("${sivan.file.root-path}")
    private String fileRootBasePath;

    private final DefaultModelRouter modelRouter;
    private final ToolRegistry toolRegistry;
    private final ToolRouter toolRouter;
    private final IToolUsageRepository toolUsageRepository;
    private final ForestAgentMessageJpaRepository a2aMessageRepository;
    private final IAgentRepository agentRepository;
    private final ISkillRepository skillRepository;
    private final PromptAssembler promptAssembler;

    public AgentLeafExecutor(DefaultModelRouter modelRouter, ToolRegistry toolRegistry, ToolRouter toolRouter, IToolUsageRepository toolUsageRepository, ForestAgentMessageJpaRepository a2aMessageRepository, IAgentRepository agentRepository, ISkillRepository skillRepository, PromptAssembler promptAssembler) {
        this.modelRouter = modelRouter;
        this.toolRegistry = toolRegistry;
        this.toolRouter = toolRouter;
        this.toolUsageRepository = toolUsageRepository;
        this.a2aMessageRepository = a2aMessageRepository;
        this.agentRepository = agentRepository;
        this.skillRepository = skillRepository;
        this.promptAssembler = promptAssembler;
    }

    @Override
    public String supportedType() {
        return "task";
    }

    @Override
    public Flux<ForestEvent> execute(TreeNode node, ExecutionContext ctx, EventSink sink) {
        UUID accountId = ctx.accountId();
        String taskContent = node.content();

        Model model;
        try {
            model = modelRouter.getDefaultModel(accountId);
        } catch (Exception e) {
            log.error("[Agent] 获取默认模型失败: {}", e.getMessage());
            return Flux.just(ForestEvent.error(node.nodeId(), null, accountId.toString(), "获取模型失败: " + e.getMessage()));
        }

        // 获取工具列表：优先使用 ToolRouter 按叶子类型路由，其次使用元数据中指定的
        List<ToolSpec> tools;
        // 构建 ForestNodeAdapter 供 ToolRouter 使用
        String agentName = node.metadataString("agentName");
        String serverId = node.metadataString("serverId");
        ForestNodeAdapter adapter;
        if (agentName != null || serverId != null) {
            UUID acctId = resolveAccountId(node);
            Map<String, String> meta = new HashMap<>();
            for (var entry : node.metadata().entrySet()) {
                if (entry.getValue() instanceof String s) {
                    meta.put(entry.getKey(), s);
                }
            }
            adapter = ForestNodeAdapter.from(node.nodeId(), supportedType(), agentName, serverId, acctId, meta);
        } else {
            adapter = ForestNodeAdapter.from(node.nodeId(), supportedType(), null, null, null, Map.of());
        }
        ExecutionContext execCtx = ctx;
        tools = toolRouter.resolve(adapter, taskContent, execCtx);
        if (tools.isEmpty()) {
            var rawSpecs = node.metadataList("preferredToolSpecs");
            if (rawSpecs != null && !rawSpecs.isEmpty()) {
                tools = rawSpecs.stream().filter(ToolSpec.class::isInstance).map(ToolSpec.class::cast).toList();
            }
        }

        // 注册 A2A 通信工具（始终可用，去重）
        List<ToolSpec> allTools = new ArrayList<>(tools);
        LinkedHashSet<String> toolNames = new LinkedHashSet<>(tools.stream().map(ToolSpec::name).toList());
        var a2aSpec = buildA2AToolSpec();
        if (toolNames.add(a2aSpec.name())) {
            allTools.add(a2aSpec);
        }

        // 获取 A2A 消息总线 and forestId（用于持久化）
        AgentMessageBus bus = ForestExecutor.activeBus();
        ConcurrentLinkedQueue<AgentMessage> pendingMessages = new ConcurrentLinkedQueue<>();
        String agentId = node.nodeId();
        UUID forestId = null;
        if (!node.metadata().isEmpty()) {
            Object raw = node.metadata().get("_forestId");
            if (raw instanceof String s && !s.isEmpty()) {
                try {
                    forestId = UUID.fromString(s);
                } catch (Exception ignored) {
                }
            }
        }

        // 订阅 A2A 消息
        var agentSub = bus.subscribe(agentId).subscribe(pendingMessages::add);
        // 订阅广播
        var broadcastSub = bus.subscribe("broadcast").subscribe(pendingMessages::add);

        log.info("[Agent] 执行任务: nodeId={} content={} 可用工具={} A2A={}", agentId, taskContent, allTools.size(), bus.activeTopics().size());

        UUID convId = ctx.conversationId();

        // 构建消息列表
        List<Msg> messages = buildMessages(node, taskContent, pendingMessages);

        return reactLoop(model, messages, allTools, node, accountId, 0, pendingMessages, bus, agentId, convId, forestId).doOnComplete(() -> log.info("[Agent] 完成: nodeId={}", agentId)).doFinally(s -> {
            agentSub.dispose();
            broadcastSub.dispose();
            log.debug("[Agent] A2A 订阅已清理: nodeId={}", agentId);
        }).onErrorResume(e -> {
            log.error("[Agent] 执行异常: {}", e.getMessage(), e);
            // 标记节点为 FAILED，避免 ForestExecutor 自动改为 COMPLETED
            if (node instanceof com.icusu.sivan.domain.forest.tree.ExecutableNode en) {
                en.setStatus(com.icusu.sivan.common.NodeStatus.FAILED);
            }
            return Flux.just(ForestEvent.error(node.nodeId(), null, accountId.toString(), "执行异常: " + e.getMessage()));
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

        return new ToolSpec("send_agent_message", "向同一任务中的其他 Agent 发送消息，用于协作、请求数据或委托子任务。" + "targetAgentId 为对方的 nodeId，可通过 A2A 消息中的 sourceAgentId 获取。", schema);
    }

    // =====================================================================
    // 消息构建
    // =====================================================================

    @SuppressWarnings("unchecked")
    private List<Msg> buildMessages(TreeNode node, String taskContent, ConcurrentLinkedQueue<AgentMessage> pendingMessages) {
        // 提取队友信息
        List<Map<String, String>> peers = null;
        Object rawPeers = node.metadata().get("peers");
        if (rawPeers instanceof List<?> list && !list.isEmpty() && list.getFirst() instanceof Map<?, ?>) {
            peers = (List<Map<String, String>>) list;
        }

        // 解析 Agent 定义的 systemPrompt 和技能
        String agentName = (node.metadata() == null ? null : node.metadataString("agentName"));
        String agentSystemPrompt = resolveAgentSystemPrompt(node, agentName);

        // SEQUENTIAL 子任务：不携带完整对话历史，仅给系统提示词 + 子任务内容
        boolean isSequentialSubtask = "true".equals(node.metadata().get("_isSequentialSubtask"));

        List<Msg> messages = null;
        if (!isSequentialSubtask) {
            Object raw = node.metadata().get("prebuiltMessages");
            if (raw instanceof List<?> list) {
                messages = new ArrayList<>(list.stream().filter(Msg.class::isInstance).map(Msg.class::cast).toList());
            }
        }
        if (messages == null || messages.isEmpty()) {
            messages = new ArrayList<>();
            messages.add(Msg.of(Role.SYSTEM, List.of(new Content.Text(agentSystemPrompt))));
            String accumulatedContext = (node.metadata() == null ? null : node.metadataString("accumulatedContext"));
            if (accumulatedContext != null && !accumulatedContext.isEmpty()) {
                messages.add(Msg.of(Role.USER, List.of(new Content.Text("以下是从之前步骤获取的结果，你可以在本次任务中参考这些信息：\n" + accumulatedContext))));
            }
            messages.add(Msg.of(Role.USER, List.of(new Content.Text(taskContent))));
        }

        // 多 Agent 协作：注入队友名片（精简，避免 token 膨胀）
        if (peers != null && !peers.isEmpty()) {
            UUID acctId = resolveAccountId(node);
            StringBuilder sb = new StringBuilder("## 团队队友\n你可以用 send_agent_message 与他们通信（targetAgentId 用下方 agentId）。\n");
            for (Map<String, String> peer : peers) {
                String peerId = peer.get("agentId");
                String task = peer.get("task");
                String peerName = resolvePeerAgentName(peer, acctId);
                // 优先显示名称，其次从任务描述提取，避免显示 UUID
                String label = peerName;
                if (label == null && task != null && !task.isBlank()) {
                    label = task.length() > 30 ? task.substring(0, 27) + "..." : task;
                }
                if (label == null) label = "队友";
                // 任务摘要截断到 50 字
                String taskBrief = task != null && task.length() > 50 ? task.substring(0, 47) + "..." : task;
                sb.append("- ").append(label).append(": ").append(taskBrief).append("\n");
            }
            sb.append("根据各自任务合理协作，不要分派队友不擅长的任务。\n" + "不要输出「我先调用」「正在等待」「让我请求」这类过程描述——直接输出你的分析结论即可。");
            messages.add(Msg.of(Role.SYSTEM, List.of(new Content.Text(sb.toString()))));
        }

        // 注入待处理的 A2A 消息
        injectPendingA2AMessages(messages, pendingMessages);
        return messages;
    }

    /**
     * 解析 Agent 的 systemPrompt：加载 Agent 定义 + 任务匹配的技能内容（组合式路由）。
     * 技能由 {@code _matchedSkillIds} 元数据决定，不从 Agent 定义中绑定。
     */
    private String resolveAgentSystemPrompt(TreeNode node, String agentName) {
        UUID accountId = null;
        if (!node.metadata().isEmpty()) {
            Object raw = node.metadata().get("_accountId");
            if (raw instanceof String s && !s.isEmpty()) {
                try {
                    accountId = UUID.fromString(s);
                } catch (Exception ignored) {
                }
            }
        }

        // 统一人格 + 统一前缀模板：身份声明 → 能力定义 → 技能参考 → 输出规范
        StringBuilder sb = new StringBuilder();
        sb.append("你是灵枢（Sivan），用户的私人 AI 智能助手。" + "你正在扮演「").append(agentName != null ? agentName : "通用助手").append("」的角色，这是 Sivan 的一项子能力。你的回答应简洁、准确、有温度。用中文回复。");

        // 1. Agent 系统提示词（具体能力描述）
        String agentSystemPrompt = null;
        if (agentName != null && !agentName.isBlank() && accountId != null) {
            try {
                var agentOpt = agentRepository.findByAccountAndName(accountId, agentName);
                if (agentOpt.isPresent()) {
                    var agent = agentOpt.get();
                    agentSystemPrompt = agent.getSystemPrompt();
                    // 记录使用次数
                    try {
                        agent.recordUsage();
                        agentRepository.save(agent);
                    } catch (Exception e) {
                        log.warn("[Agent] 记录使用统计失败: name={} error={}", agentName, e.getMessage());
                    }
                }
            } catch (Exception e) {
                log.warn("[Agent] 加载 Agent 定义失败: name={} error={}", agentName, e.getMessage());
            }
        }
        if (agentSystemPrompt != null && !agentSystemPrompt.isBlank()) {
            sb.append("\n\n## 能力说明\n").append(agentSystemPrompt);
        }

        // 2. 任务匹配的技能
        List<String> matchedSkillIds = extractMatchedSkillIds(node);
        if (!matchedSkillIds.isEmpty()) {
            sb.append("\n\n## 技能参考\n");
            for (String skillId : matchedSkillIds) {
                try {
                    var skillOpt = skillRepository.findById(UUID.fromString(skillId));
                    skillOpt.ifPresent(skill -> {
                        sb.append("\n### ").append(skill.getName()).append("\n");
                        if (skill.getContent() != null && !skill.getContent().isBlank()) {
                            sb.append(skill.getContent()).append("\n");
                        }
                    });
                } catch (Exception ignored) {
                }
            }
        }

        sb.append("\n\n## 输出规范\n- 直接输出分析结果，不要输出内部思考过程或协作步骤\n" + "- 不要输出「我先做X」「正在等待Y」「让我调用Z」这类过程描述\n" + "- 读取文件内容后，不要将完整文件内容输出到对话中——用 file_edit/file_write 修改即可\n" + "- 其他 Agent 的分析结果会由主控整合给你，你不需要等待或催促他们\n" + "- 不要在回复中使用分隔线、装饰符号自我介绍——直接回答问题\n" + "- 只输出结论，不言自语\n\n" + "你可以使用 send_agent_message 工具与同一任务中的其他 Agent 协作。");

        return sb.toString();
    }

    /**
     * 从节点 metadata 提取任务匹配的技能 ID 列表。
     */
    private List<String> extractMatchedSkillIds(TreeNode node) {
        if (node instanceof ContentNode cn) {
            Object raw = cn.metadata().get("_matchedSkillIds");
            if (raw instanceof String s && !s.isBlank()) {
                return List.of(s.split(","));
            }
        }
        return List.of();
    }

    /**
     * 从节点 metadata 中解析 accountId。
     */
    private UUID resolveAccountId(TreeNode node) {
        if (!node.metadata().isEmpty()) {
            Object raw = node.metadata().get("_accountId");
            if (raw instanceof String s && !s.isEmpty()) {
                try {
                    return UUID.fromString(s);
                } catch (Exception ignored) {
                }
            }
        }
        return null;
    }

    /**
     * 查询队友的 Agent 显示名称。
     */
    @SuppressWarnings("unchecked")
    private String resolvePeerAgentName(Map<String, String> peer, UUID accountId) {
        if (accountId == null) return null;
        Object raw = peer.get("agentName");
        if (raw instanceof String name && !name.isBlank()) {
            try {
                var opt = agentRepository.findByAccountAndName(accountId, name);
                if (opt.isPresent() && opt.get().getDisplayName() != null) {
                    return opt.get().getDisplayName();
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    /**
     * 查询队友的能力摘要（从 Agent 定义的 description 和技能名中提取）。
     */
    @SuppressWarnings("unchecked")
    private String resolvePeerCapabilities(Map<String, String> peer, UUID accountId) {
        if (accountId == null) return null;
        Object raw = peer.get("agentName");
        if (raw instanceof String name && !name.isBlank()) {
            try {
                var opt = agentRepository.findByAccountAndName(accountId, name);
                if (opt.isPresent()) {
                    var agent = opt.get();
                    StringBuilder caps = new StringBuilder();
                    if (agent.getDescription() != null && !agent.getDescription().isBlank()) {
                        caps.append(agent.getDescription());
                    }
                    if (agent.getSkillIds() != null && !agent.getSkillIds().isEmpty()) {
                        if (!caps.isEmpty()) caps.append("；");
                        caps.append("技能: ");
                        List<String> skillNames = new java.util.ArrayList<>();
                        for (String sid : agent.getSkillIds()) {
                            try {
                                skillRepository.findById(UUID.fromString(sid)).ifPresent(s -> skillNames.add(s.getName()));
                            } catch (Exception ignored) {
                            }
                        }
                        caps.append(String.join("、", skillNames));
                    }
                    return !caps.isEmpty() ? caps.toString() : null;
                }
            } catch (Exception ignored) {
            }
        }
        return null;
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
            messages.add(Msg.of(Role.USER, List.of(new Content.Text(prefix + "\n" + msg.content()))));
        }
    }

    // =====================================================================
    // ReAct 循环
    // =====================================================================

    private Flux<ForestEvent> reactLoop(Model model, List<Msg> messages, List<ToolSpec> tools, TreeNode node, UUID accountId, int round, ConcurrentLinkedQueue<AgentMessage> pendingMessages, AgentMessageBus bus, String agentId, UUID convId, UUID forestId) {
        if (round >= maxRounds) {
            log.warn("[Agent] 达到最大轮数: nodeId={} maxRounds={}", node.nodeId(), maxRounds);
            return Flux.just(ForestEvent.error(node.nodeId(), null, accountId.toString(), "达到最大工具调用轮数: " + maxRounds));
        }

        return Flux.defer(() -> {
            StringBuilder textAcc = new StringBuilder();
            StringBuilder thinkAcc = new StringBuilder();
            Map<Integer, ToolCallAcc> toolAccs = new HashMap<>();
            final TokenUsage[] lastTokenUsage = {null};

            // 去重：DeepSeek 等 API 要求工具名唯一
            List<ToolSpec> dedupedTools = tools.stream().collect(Collectors.toMap(ToolSpec::name, t -> t, (a, b) -> a, LinkedHashMap::new)).values().stream().toList();
            return model.stream(messages, dedupedTools, Model.ModelParams.defaults()).concatMap(chunk -> {
                if (chunk.usage() != null) lastTokenUsage[0] = chunk.usage();
                List<ForestEvent> events = new ArrayList<>(2);
                if (!chunk.content().isEmpty()) {
                    textAcc.append(chunk.content());
                    events.add(ForestEvent.detail(node.nodeId(), null, accountId.toString(), chunk.content()));
                }
                if (!chunk.thinking().isEmpty()) {
                    thinkAcc.append(chunk.thinking());
                    events.add(ForestEvent.thinking(node.nodeId(), null, accountId.toString(), chunk.thinking()));
                }
                for (var d : chunk.toolCallDeltas()) {
                    var acc = toolAccs.computeIfAbsent(d.index(), k -> new ToolCallAcc());
                    if (d.id() != null) acc.id = d.id();
                    if (d.name() != null) acc.name = d.name();
                    if (d.arguments() != null) acc.args.append(d.arguments());
                }
                return Flux.fromIterable(events);
            }).concatWith(Flux.defer(() -> {
                List<Content> contents = new ArrayList<>();
                if (!thinkAcc.isEmpty()) {
                    contents.add(new Content.Thinking(thinkAcc.toString(), ""));
                }
                if (!textAcc.isEmpty()) {
                    contents.add(new Content.Text(textAcc.toString()));
                }
                for (ToolCallAcc acc : toolAccs.values()) {
                    if (acc.id == null || acc.id.isBlank()) {
                        log.warn("[Agent] 跳过 ID 为空 tool call: name={}", acc.name);
                        continue;
                    }
                    contents.add(new Content.ToolCall(acc.id, acc.name != null ? acc.name : "", parseToolArgs(acc.args.toString())));
                }

                List<Msg> newMessages = new ArrayList<>(messages.size() + 1);
                newMessages.addAll(messages);
                newMessages.add(Msg.of(Role.ASSISTANT, contents));

                List<Content.ToolCall> toolCalls = contents.stream().filter(c -> c instanceof Content.ToolCall).map(c -> (Content.ToolCall) c).toList();

                if (toolCalls.isEmpty()) {
                    // 保存节点产出到 metadata，供执行树展示
                    if (node instanceof com.icusu.sivan.domain.forest.tree.ContentNode cn) {
                        String output = textAcc.toString();
                        if (!output.isBlank()) cn.metadata().put("output", output);
                        String thinking = thinkAcc.toString();
                        if (!thinking.isBlank()) cn.metadata().put("thinking", thinking);
                    }
                    // 本轮完成，发射 token 用量
                    int totalTokens = lastTokenUsage[0] != null ? lastTokenUsage[0].totalTokens() : 0;
                    int thinkingTokens = lastTokenUsage[0] != null ? lastTokenUsage[0].thinkingTokens() : 0;
                    ForestEvent tokenEvent = new ForestEvent(node.nodeId(), null, accountId.toString(), ForestEvent.EventType.MILESTONE, "{\"totalTokens\":" + totalTokens + ",\"thinkingTokens\":" + thinkingTokens + "}");
                    return Flux.just(tokenEvent);
                }

                log.info("[Agent] 工具调用: {}", toolCalls.stream().map(Content.ToolCall::name).toList());

                // 先检查 A2A 消息，再处理其他工具
                List<Content.ToolCall> a2aCalls = toolCalls.stream().filter(tc -> "send_agent_message".equals(tc.name())).toList();
                List<Content.ToolCall> otherCalls = toolCalls.stream().filter(tc -> !"send_agent_message".equals(tc.name())).toList();

                // 处理 A2A 消息：发布到总线
                for (Content.ToolCall a2aCall : a2aCalls) {
                    Map<String, Object> args = a2aCall.args() != null ? a2aCall.args() : Map.of();
                    String targetId = (String) args.get("targetAgentId");
                    String content = (String) args.get("content");
                    String typeStr = (String) args.get("type");
                    AgentMessage.MessageType msgType = "BROADCAST".equals(typeStr) ? AgentMessage.MessageType.BROADCAST : AgentMessage.MessageType.REQUEST;
                    // 如果内容是 RESPONSE 格式，自动转为 RESPONSE 类型
                    if ("RESPONSE".equals(typeStr)) msgType = AgentMessage.MessageType.RESPONSE;
                    AgentMessage a2aMsg = new AgentMessage(agentId, targetId, "RESPONSE".equals(typeStr) ? targetId : targetId, content != null ? content : "", msgType);
                    bus.publish(a2aMsg);
                    persistA2AMessage(forestId, node.nodeId(), agentId, targetId, targetId, content, msgType.name());
                    log.info("[A2A] {} → {}: {}", agentId.substring(0, 8), targetId.length() > 8 ? targetId.substring(0, 8) : targetId, truncateStr(content, 50));
                }

                if (!otherCalls.isEmpty()) {
                    // 有工具调用时，A2A 消息注入延后到 handleToolCalls 内
                    // 在 tool results 之后注入，避免破坏 tool_calls → tool_result 的相邻约束
                    return handleToolCalls(model, newMessages, tools, node, accountId, round, otherCalls, pendingMessages, bus, agentId, convId, forestId);
                }
                // 仅 A2A 消息：添加 tool results 后再继续，满足 tool_calls → tool_result 约束
                List<Msg> messagesWithA2AResults = new ArrayList<>(newMessages);
                for (Content.ToolCall a2aCall : a2aCalls) {
                    messagesWithA2AResults.add(Msg.of(Role.TOOL, List.of(new Content.ToolResult(a2aCall.id(), true, "A2A 消息已发送"))));
                }
                injectPendingA2AMessages(messagesWithA2AResults, pendingMessages);
                return reactLoop(model, messagesWithA2AResults, tools, node, accountId, round + 1, pendingMessages, bus, agentId, convId, forestId);
            }));
        });
    }

    // =====================================================================
    // 工具调用
    // =====================================================================

    private Flux<ForestEvent> handleToolCalls(Model model, List<Msg> messages, List<ToolSpec> tools, TreeNode node, UUID accountId, int round, List<Content.ToolCall> toolCalls, ConcurrentLinkedQueue<AgentMessage> pendingMessages, AgentMessageBus bus, String agentId, UUID convId, UUID forestId) {
        Flux<ForestEvent> callEvents = Flux.fromIterable(toolCalls).map(tc -> ForestEvent.toolCall(node.nodeId(), null, accountId.toString(), json("name", tc.name(), "args", toJsonString(tc.args()), "id", tc.id())));

        List<Mono<ToolCallResult>> monos = toolCalls.stream().map(tc -> executeSingleTool(tc, accountId, node, convId).cache()).toList();

        Flux<ForestEvent> resultEvents = Flux.mergeSequential(monos).flatMap(r -> {
            // 工具结果事件仅记录名称和成功状态，不包含输出内容（避免日志和对话膨胀）
            ForestEvent event = ForestEvent.toolResult(node.nodeId(), null, accountId.toString(), "{\"name\":\"" + r.name() + "\",\"success\":" + r.success() + "}");
            return Mono.just(event);
        }).concatWith(Flux.defer(() -> collectResults(monos).flatMapMany(results -> {
            List<Msg> newMessages = new ArrayList<>(messages);
            for (var r : results) {
                newMessages.add(Msg.of(Role.TOOL, List.of(new Content.ToolResult(r.id(), r.success(), r.content()))));
            }
            injectPendingA2AMessages(newMessages, pendingMessages);
            return reactLoop(model, newMessages, tools, node, accountId, round + 1, pendingMessages, bus, agentId, convId, forestId);
        })));

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
            if (!toolNode.metadata().isEmpty()) {
                Object frp = toolNode.metadata().get("_fileRootPath");
                if (frp instanceof String s && !s.isEmpty()) {
                    // metadata 中存储的是相对路径，工具执行需要绝对路径
                    String absPath = fileRootBasePath + "/" + s;
                    mergedArgs.put("_fileRootPath", absPath);
                }
                Object archived = toolNode.metadata().get("_archived");
                if (archived instanceof Boolean b) mergedArgs.put("_archived", b);
            }
            enhancedTc = new Content.ToolCall(tc.id(), tcName, mergedArgs);
        }
        Map<String, Object> attrs = new java.util.HashMap<>(Map.of("_accountId", acctId.toString()));
        if (!toolNode.metadata().isEmpty()) {
            Object kbNames = toolNode.metadata().get("_kbNames");
            if (kbNames instanceof String s && !s.isEmpty()) attrs.put("_kbNames", s);
        }
        var toolCtx = com.icusu.sivan.core.context.ExecutionContext.create(null, List.of(), attrs);
        return executor.execute(enhancedTc, toolCtx).map(r -> new ToolCallResult(tc.id(), tc.name(), r.success(), truncateOutput(r.output()))).doOnNext(r -> recordToolUsage(tc.name(), acctId, toolNode, r.success(), (int) (System.currentTimeMillis() - toolStartMs), convId)).timeout(Duration.ofMinutes(5), Mono.just(new ToolCallResult(tc.id(), tc.name(), false, "工具执行超时"))).onErrorResume(e -> {
            log.warn("[Agent] 工具执行失败: {} error={}", tc.name(), e.getMessage());
            recordToolUsage(tc.name(), acctId, toolNode, false, (int) (System.currentTimeMillis() - toolStartMs), convId);
            return Mono.just(new ToolCallResult(tc.id(), tc.name(), false, e.getMessage()));
        });
    }

    private Mono<List<Content.ToolResult>> collectResults(List<Mono<ToolCallResult>> monos) {
        return Flux.mergeSequential(monos).map(r -> new Content.ToolResult(r.id(), r.success(), r.output())).collectList();
    }

    // =====================================================================
    // 内部类型 & 工具方法
    // =====================================================================

    private record ToolCallResult(String id, String name, boolean success, String output) {
    }

    private static class ToolCallAcc {
        String id;
        String name;
        final StringBuilder args = new StringBuilder();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseToolArgs(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return MAPPER.readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private void persistA2AMessage(UUID forestId, String scopeNodeId, String sourceAgent, String targetAgent, String topic, String content, String msgType) {
        if (forestId == null) return;
        try {
            a2aMessageRepository.save(ForestAgentMessageEntity.builder().forestId(forestId).scopeNodeId(scopeNodeId).sourceAgent(sourceAgent).targetAgent(targetAgent).topic(topic).messageType(msgType).payload(content).build());
        } catch (Exception e) {
            log.debug("A2A 消息持久化失败（不影响执行）: {}", e.getMessage());
        }
    }

    private void recordToolUsage(String toolName, UUID acctId, TreeNode toolNode, boolean success, int durationMs, UUID convId) {
        try {
            toolUsageRepository.save(ToolUsage.builder().accountId(acctId).agentName(toolNode != null ? toolNode.nodeId() : null).toolName(toolName).serverId("").success(success).durationMs(durationMs).conversationId(convId).build());
        } catch (Exception e) {
            log.debug("工具使用记录失败（不影响执行）: {}", e.getMessage());
        }
    }

    private static String truncateOutput(String output) {
        if (output == null || output.length() <= MAX_TOOL_OUTPUT_CHARS) return output;
        return output.substring(0, MAX_TOOL_OUTPUT_CHARS) + "\n\n[... 截断: 原始输出 " + output.length() + " 字符]";
    }

    private static String json(String... pairs) {
        try {
            var map = new java.util.LinkedHashMap<String, String>();
            for (int i = 0; i < pairs.length; i += 2) {
                if (i + 1 < pairs.length) map.put(pairs[i], pairs[i + 1]);
            }
            return MAPPER.writeValueAsString(map);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static String toJsonString(Object obj) {
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static String truncateStr(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
