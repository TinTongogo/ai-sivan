package com.icusu.sivan.agent.strategy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.icusu.sivan.core.agent.Agent;
import com.icusu.sivan.core.agent.AgentEvent;
import com.icusu.sivan.core.agent.AgentResult;
import com.icusu.sivan.core.agent.ExecutionStrategy;
import com.icusu.sivan.core.context.ExecutionContext;
import com.icusu.sivan.core.message.Content;
import com.icusu.sivan.core.message.Msg;
import com.icusu.sivan.core.message.Role;
import com.icusu.sivan.core.model.Model;
import com.icusu.sivan.core.model.TokenUsage;
import com.icusu.sivan.core.tool.ToolProvider;
import com.icusu.sivan.core.tool.ToolSpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ReAct 执行策略：LLM 调用 → 工具执行 → LLM 再调用。
 * 循环限制可通过配置调整（sivan.agent.max-rounds 等）。
 *
 * <p>工具来源优先级：
 * <ol>
 *   <li>{@link ExecutionContext#attribute(String)} 中 {@code "_tools"} 键对应的 {@code List<ToolSpec>}</li>
 *   <li>{@link ToolProvider#listTools()}</li>
 * </ol>
 */
@Slf4j
@Component
public class ReActExecutionStrategy implements ExecutionStrategy {

    /** 最大 ReAct 轮数（安全阀，默认 200 极少触发，实际由上下文预算控制）。 */
    @Value("${sivan.agent.max-rounds:200}")
    private int maxRounds;

    /** 连续相同工具调用上限。 */
    @Value("${sivan.agent.max-consecutive-same-tool:15}")
    private int maxConsecutiveSameTool;

    /** 连续工具失败上限，超限后终止。 */
    @Value("${sivan.agent.max-failed-rounds:3}")
    private int maxFailedRounds;

    /**
     * 上下文预算比例（0.0~1.0）。
     * ReAct 累计消息估算 token 超过 model.contextLength × budgetRatio 时触发压缩。
     */
    @Value("${sivan.agent.context-budget-ratio:0.7}")
    private double contextBudgetRatio;

    /** 压缩后保留的最后 N 轮 ToolResult，其余旧轮次丢弃。 */
    @Value("${sivan.agent.context-compress-keep-rounds:5}")
    private int compressKeepRounds;

    private int effectiveMaxRounds() { return maxRounds > 0 ? maxRounds : 200; }
    private int effectiveMaxConsecutive() { return maxConsecutiveSameTool > 0 ? maxConsecutiveSameTool : 15; }
    private int effectiveMaxFailed() { return maxFailedRounds > 0 ? maxFailedRounds : 3; }
    private double effectiveBudgetRatio() { return contextBudgetRatio > 0 && contextBudgetRatio <= 1.0 ? contextBudgetRatio : 0.7; }
    private int effectiveCompressKeepRounds() { return compressKeepRounds > 0 ? compressKeepRounds : 5; }

    /** 单条工具结果最大字符数，超出尾部截断。LLM 已消费的推理不依赖原文，截断不影响后续轮次。 */
    /** 单条工具结果最大字符数，超出尾部截断。LLM 已消费的推理不依赖原文，截断不影响后续轮次。 */
    private static final int MAX_TOOL_OUTPUT_CHARS = 3000;

    /** 当 model 未设置 contextLength 时的默认预算（16K token）。 */
    private static final int DEFAULT_CONTEXT_BUDGET = 16_384;

    /** token 估算系数：每字符约 0.25 token。 */
    private static final double TOKEN_RATIO = 0.25;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static final String ATTR_TOOLS = "_tools";
    public static final String ATTR_STREAM = "_stream";
    public static final String ATTR_PARAMS = "_params";

    @Override
    public Flux<AgentEvent> execute(Agent agent, ExecutionContext ctx) {
        Model.ModelParams params = ctx.attribute(ATTR_PARAMS);
        if (params == null) {
            params = Model.ModelParams.defaults().withPrefixCaching(true);
        }

        List<ToolSpec> tools = ctx.attribute(ATTR_TOOLS);
        if (tools == null || tools.isEmpty()) {
            tools = agent.toolProvider().listTools();
        }

        Boolean stream = ctx.attribute(ATTR_STREAM);
        boolean isStreaming = stream == null || stream;

        String fileRootPath = ctx.attribute("_fileRootPath");
        boolean archived = Boolean.TRUE.equals(ctx.attribute("_archived"));

        // 计算上下文预算：model.contextLength × budgetRatio，如未设置则用默认值
        int ctxLen = params.contextLength() != null ? params.contextLength() : DEFAULT_CONTEXT_BUDGET;
        int contextBudget = (int) (ctxLen * effectiveBudgetRatio());
        int compressKeep = effectiveCompressKeepRounds();

        log.debug("ReAct 上下文预算: contextLength={}, budget={}, budgetRatio={}, compressKeepRounds={}",
                ctxLen, contextBudget, effectiveBudgetRatio(), compressKeep);

        ReActState state = ReActState.initial(ctx.messages(), contextBudget, compressKeep);
        return executeRound(agent, state, tools, params, isStreaming, fileRootPath, archived)
                .timeout(Duration.ofMinutes(5));
    }

    private Flux<AgentEvent> executeRound(
            Agent agent, ReActState state, List<ToolSpec> tools, Model.ModelParams params,
            boolean isStreaming, String fileRootPath, boolean archived) {

        if (state.round() >= effectiveMaxRounds()) {
            return Flux.just(new AgentEvent.Completed(
                    AgentResult.success(agent.agentId(), state.messages(), state.usage())));
        }

        // 全流程流式：实时输出文本，流结束后检查工具调用
        if (isStreaming) {
            return executeStreamRound(agent, state, tools, params)
                    .concatMap(event -> {
                        if (event instanceof AgentEvent.Completed completed) {
                            List<Msg> streamMsgs = completed.result().msgs();
                            TokenUsage streamUsage = completed.result().usage();
                            ReActState streamState = state.withMessages(streamMsgs).withUsage(streamUsage);
                            if (!hasToolCalls(streamMsgs)) {
                                return Flux.just(event);
                            }
                            log.info("流式发现工具调用 → processReply: {} tools",
                                    streamMsgs.getLast().contents().stream().filter(c -> c instanceof Content.ToolCall).count());
                            return processReply(agent, streamState, tools, params, isStreaming, fileRootPath, archived);
                        }
                        return Flux.just(event);
                    })
                    .onErrorResume(e -> fallbackOrFail(agent, state, tools, params,
                            isStreaming, e, fileRootPath, archived));
        }

        // 非流式回退（isStreaming=false 时）
        return agent.model().chat(state.messages(), tools, params)
                .flatMapMany(response -> {
                    Msg reply = response.msg();
                    TokenUsage merged = safeMerge(state.usage(), response.usage());
                    ReActState newState = state.addReply(reply).withUsage(merged);
                    Flux<AgentEvent> prefix = buildReplyPrefix(reply);
                    return prefix.concatWith(processReply(agent, newState, tools, params,
                            isStreaming, fileRootPath, archived));
                })
                .onErrorResume(e -> fallbackOrFail(agent, state, tools, params,
                        isStreaming, e, fileRootPath, archived));
    }

    /** 模型调用失败时，若携带工具则降级重试（不带工具），否则返回友好错误。 */
    private Flux<AgentEvent> fallbackOrFail(Agent agent, ReActState state,
                                           List<ToolSpec> tools, Model.ModelParams params,
                                           boolean isStreaming,
                                           Throwable error, String fileRootPath, boolean archived) {
        if (!tools.isEmpty()) {
            log.warn("模型({})调用失败（携带{}个工具），降级重试（无工具）: {}",
                    agent.model().modelId(), tools.size(), error.getMessage());
            if (isStreaming) {
                return executeStreamRound(agent, state, List.of(), params)
                        .concatMap(event -> {
                            if (event instanceof AgentEvent.Completed c) {
                                return Flux.just(c);
                            }
                            return Flux.just(event);
                        })
                        .onErrorResume(e2 -> Flux.just(
                                new AgentEvent.Error(e2),
                                new AgentEvent.Completed(
                                        AgentResult.failure(agent.agentId(), e2.getMessage()))));
            }
            return agent.model().chat(state.messages(), List.of(), params)
                    .flatMapMany(response -> {
                        Msg reply = response.msg();
                        TokenUsage merged = safeMerge(state.usage(), response.usage());
                        ReActState newState = state.addReply(reply).withUsage(merged);
                        return buildReplyPrefix(reply).concatWith(
                                processReply(agent, newState, List.of(), params, isStreaming,
                                        fileRootPath, archived));
                    })
                    .onErrorResume(e2 -> Flux.just(
                            new AgentEvent.Error(e2),
                            new AgentEvent.Completed(
                                    AgentResult.failure(agent.agentId(), e2.getMessage()))));
        }
        return Flux.just(
                new AgentEvent.Error(error),
                new AgentEvent.Completed(
                        AgentResult.failure(agent.agentId(), error.getMessage())));
    }

    /** 流式执行，实时发射 Chunk/Thinking/工具调用。 */
    private Flux<AgentEvent> executeStreamRound(Agent agent, ReActState state,
                                                 List<ToolSpec> tools, Model.ModelParams params) {
        return Flux.defer(() -> {
            StringBuilder textAcc = new StringBuilder();
            StringBuilder thinkAcc = new StringBuilder();
            TokenUsage[] usageHolder = new TokenUsage[]{state.usage()};
            Map<Integer, ToolCallAcc> toolAccs = new HashMap<>();

            return agent.model().stream(state.messages(), tools, params)
                    .concatMap(chunk -> {
                        List<AgentEvent> evts = new ArrayList<>();
                        if (!chunk.content().isEmpty()) {
                            textAcc.append(chunk.content());
                            evts.add(new AgentEvent.Chunk(chunk.content()));
                        }
                        if (!chunk.thinking().isEmpty()) {
                            thinkAcc.append(chunk.thinking());
                            evts.add(new AgentEvent.Thinking(chunk.thinking()));
                        }
                        for (var d : chunk.toolCallDeltas()) {
                            var acc = toolAccs.computeIfAbsent(d.index(), k -> new ToolCallAcc());
                            if (d.id() != null) acc.id = d.id();
                            if (d.name() != null) acc.name = d.name();
                            if (d.arguments() != null) acc.args.append(d.arguments());
                        }
                        if (chunk.usage() != null && chunk.usage().totalTokens() > 0) {
                            usageHolder[0] = safeMerge(usageHolder[0], chunk.usage());
                        }
                        return Flux.fromIterable(evts);
                    })
                    .concatWith(Mono.fromCallable(() -> {
                        List<Content> contents = new ArrayList<>();
                        if (!thinkAcc.isEmpty()) {
                            contents.add(new Content.Thinking(thinkAcc.toString(), ""));
                        }
                        if (!textAcc.isEmpty()) {
                            contents.add(new Content.Text(textAcc.toString()));
                        }
                        for (ToolCallAcc acc : toolAccs.values()) {
                            Map<String, Object> parsedArgs = parseToolArgs(acc.args.toString());
                            contents.add(new Content.ToolCall(acc.id != null ? acc.id : "", acc.name != null ? acc.name : "", parsedArgs));
                        }
                        Msg reply = Msg.of(Role.ASSISTANT, contents);
                        List<Msg> newMessages = new ArrayList<>(state.messages().size() + 1);
                        newMessages.addAll(state.messages());
                        newMessages.add(reply);
                        return new AgentEvent.Completed(
                                AgentResult.success(agent.agentId(), List.copyOf(newMessages), usageHolder[0]));
                    }));
        });
    }

    /** 截断单条工具输出，保留头部配合尾部截断标记，让 LLM 知道内容不完整。 */
    private static String truncateToolOutput(String output) {
        if (output == null || output.length() <= MAX_TOOL_OUTPUT_CHARS) return output;
        return output.substring(0, MAX_TOOL_OUTPUT_CHARS)
                + "\n\n[... 截断: 原始输出 " + output.length() + " 字符，可请求读取完整内容]";
    }

    private static Map<String, Object> parseToolArgs(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return MAPPER.readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static boolean hasToolCalls(List<Msg> messages) {
        Msg last = messages.getLast();
        boolean found = last.contents().stream().anyMatch(c -> c instanceof Content.ToolCall);
        if (log.isDebugEnabled()) {
            log.debug("hasToolCalls={} msg.role={} contents={}", found, last.role(), last.contents().size());
        }
        return found;
    }

    private static class ToolCallAcc {
        String id, name;
        StringBuilder args = new StringBuilder();
    }

    /** 构建非流式回复的前缀事件（文本/思考），流式路径已在 executeStreamRound 中逐 chunk 发射。 */
    private Flux<AgentEvent> buildReplyPrefix(Msg reply) {
        List<AgentEvent> events = new ArrayList<>();
        String thinking = reply.thinking();
        if (thinking != null && !thinking.isEmpty()) {
            events.add(new AgentEvent.Thinking(thinking));
        }
        String text = reply.text();
        if (text != null && !text.isEmpty()) {
            events.add(new AgentEvent.Chunk(text));
        }
        return Flux.fromIterable(events);
    }

    /** 处理回复中的工具调用，注意：调用方已负责发射文本/思考事件，此处不再重复发射。 */
    private Flux<AgentEvent> processReply(Agent agent, ReActState state, List<ToolSpec> tools,
                                           Model.ModelParams params, boolean isStreaming,
                                           String fileRootPath, boolean archived) {

        Msg reply = state.messages().getLast();
        List<Content.ToolCall> toolCalls = reply.contents().stream()
                .filter(c -> c instanceof Content.ToolCall)
                .map(c -> (Content.ToolCall) c)
                .toList();

        if (toolCalls.isEmpty()) {
            return Flux.just(new AgentEvent.Completed(
                    AgentResult.success(agent.agentId(), state.messages(), state.usage())));
        }

        // 连续相同工具检测
        List<String> distinctNames = toolCalls.stream()
                .map(Content.ToolCall::name)
                .filter(n -> !"__fragment__".equals(n))
                .distinct().toList();
        String currentToolName = distinctNames.size() == 1 ? distinctNames.get(0) : null;
        int nextConsecutive = (currentToolName != null && currentToolName.equals(state.lastToolName()))
                ? state.consecutiveSameTool() + 1 : 0;

        if (nextConsecutive > effectiveMaxConsecutive()) {
            return Flux.just(new AgentEvent.Completed(
                    AgentResult.success(agent.agentId(), state.messages(), state.usage())));
        }

        // 发射 ToolCall 事件
        List<AgentEvent> toolCallEvents = toolCalls.stream()
                .map(tc -> (AgentEvent) new AgentEvent.ToolCall(tc.id(), tc.name(), tc.args()))
                .toList();

        return executeTools(agent, toolCalls, fileRootPath, archived)
                .flatMapMany(results -> {
                    // 建立 call ID → tool name 映射，确保 ToolResult 携带工具名供前端匹配
                    Map<String, String> toolNameById = new HashMap<>();
                    for (Content.ToolCall tc : toolCalls) {
                        if (tc.id() != null && !tc.id().isEmpty()) {
                            toolNameById.put(tc.id(), tc.name());
                        }
                    }
                    List<AgentEvent> toolResultEvents = results.stream()
                            .map(r -> (AgentEvent) new AgentEvent.ToolResult(
                                    r.id(), toolNameById.getOrDefault(r.id(), ""), r.success(), r.content()))
                            .toList();

                    ReActState toolResultState = state.addToolResults(results);

                    int failures = (int) results.stream().filter(r -> !r.success()).count();
                    int nextFailed = (toolCalls.size() > 0 && failures == toolCalls.size())
                            ? state.failedRoundCount() + 1 : 0;

                    if (nextFailed > effectiveMaxFailed()) {
                        return Flux.concat(
                                Flux.fromIterable(toolCallEvents),
                                Flux.fromIterable(toolResultEvents),
                                Flux.just(new AgentEvent.Completed(
                                        AgentResult.success(agent.agentId(),
                                                toolResultState.messages(), toolResultState.usage())))
                        );
                    }

                    ReActState nextState = toolResultState.advanceRound(
                            currentToolName, nextConsecutive, nextFailed);

                    return Flux.concat(
                            Flux.fromIterable(toolCallEvents),
                            Flux.fromIterable(toolResultEvents),
                            executeRound(agent, nextState, tools, params,
                                    isStreaming, fileRootPath, archived)
                    );
                });
    }

    /**
     * 并行执行多个工具，返回 {@link Content.ToolResult} 列表（顺序与输入一致）。
     * 自动注入 _fileRootPath 和 _archived（LLM 不传时兜底）。
     */
    private Mono<List<Content.ToolResult>> executeTools(
            Agent agent, List<Content.ToolCall> toolCalls, String fileRootPath, boolean archived) {

        log.info("executeTools: provider={} tools={}", agent.toolProvider().getClass().getSimpleName(),
                toolCalls.stream().map(Content.ToolCall::name).toList());
        List<Mono<Content.ToolResult>> monos = toolCalls.stream()
                .map(tc -> {
                    Map<String, Object> args = new HashMap<>(tc.args());
                    // 强制注入项目根目录（始终覆盖 LLM 传入的值，防止路径劫持）
                    if (fileRootPath != null) {
                        args.put("_fileRootPath", fileRootPath);
                    }
                    args.put("_archived", archived);
                    return agent.toolProvider()
                        .execute(tc.name(), args)
                        .timeout(Duration.ofSeconds(30))
                        .doOnNext(r -> log.info("工具结果: name={} success={} outputLen={}", tc.name(), r.success(), r.output() != null ? r.output().length() : 0))
                        .map(r -> new Content.ToolResult(tc.id(), r.success(), truncateToolOutput(r.output())))
                        .onErrorResume(e -> {
                            log.warn("工具执行异常: name={} error={}", tc.name(), e.getMessage());
                            return Mono.just(new Content.ToolResult(tc.id(), false, e.getMessage()));
                        });
                })
                .toList();

        return Mono.zipDelayError(monos, raw -> {
            Content.ToolResult[] results = new Content.ToolResult[raw.length];
            for (int i = 0; i < raw.length; i++) {
                results[i] = (Content.ToolResult) raw[i];
            }
            return List.of(results);
        });
    }

    private static TokenUsage safeMerge(TokenUsage a, TokenUsage b) {
        if (b == null) return a;
        return new TokenUsage(
                a.promptTokens() + b.promptTokens(),
                a.completionTokens() + b.completionTokens(),
                a.totalTokens() + b.totalTokens(),
                a.thinkingTokens() + b.thinkingTokens());
    }

    /**
     * ReAct 循环的不可变状态记录。
     * <p>每次"状态变更"都创建新实例，消除递归链中共享可变 ArrayList 的设计缺陷。</p>
     * <p>上下文预算控制：当累计消息估算 token 超过 contextBudget 时，自动压缩旧轮次的 ToolResult，
     * 仅保留最近 compressKeepRounds 轮的结果，让 ReAct 可以持续运行直到自然结束。</p>
     */
    private record ReActState(
            List<Msg> messages,
            int round,
            TokenUsage usage,
            String lastToolName,
            int consecutiveSameTool,
            int failedRoundCount,
            int contextBudget,
            int compressKeepRounds
    ) {
        ReActState {
            messages = List.copyOf(messages);
        }

        static ReActState initial(List<Msg> msgs, int contextBudget, int compressKeepRounds) {
            return new ReActState(msgs, 0, TokenUsage.EMPTY, null, 0, 0,
                    contextBudget, compressKeepRounds);
        }

        /** 估算当前消息列表的 token 数（字符数 × 0.25）。 */
        int estimatedTokens() {
            return messages.stream().mapToInt(m -> (int) (m.text().length() * TOKEN_RATIO)).sum();
        }

        /** 上下文预算是否已用完，用完后需要压缩。 */
        boolean isOverBudget() {
            return estimatedTokens() > contextBudget;
        }

        /**
         * 压缩旧轮次的 ToolResult。
         * 保留：系统提示 + 用户任务消息 + 最近 compressKeepRounds 轮的 ToolResult。
         * 丢弃：早期 ToolResult（对应的 Assistant 消息保留，因为包含了 LLM 的推理过程和工具调用声明）。
         */
        ReActState compressIfNeeded() {
            if (!isOverBudget()) return this;

            // 从后往前扫描，找到最后 compressKeepRounds 个包含 ToolCall 的 Assistant 消息，
            // 只保留这些轮次的 ToolResult
            int keepMsgs = 0;
            int foundRounds = 0;
            for (int i = messages.size() - 1; i >= 0; i--) {
                keepMsgs++;
                Msg m = messages.get(i);
                if (m.role() == Role.ASSISTANT && hasToolCalls(List.of(m))) {
                    foundRounds++;
                    if (foundRounds >= compressKeepRounds) break;
                }
            }

            // 保留：头部消息（系统提示等）+ 尾部 keepMsgs 条消息
            int headCount = Math.max(1, messages.size() - keepMsgs);
            // 找到第一个 TOOL 类型的消息位置，从那里开始截断
            int firstToolIdx = -1;
            for (int i = 0; i < headCount; i++) {
                if (messages.get(i).role() == Role.TOOL) {
                    firstToolIdx = i;
                    break;
                }
            }

            if (firstToolIdx < 0) return this; // 没有可压缩的 ToolResult

            // 防孤立：如果 firstToolIdx 前一条是带 tool_calls 的 Assistant，其 ToolResult
            // 位于 [firstToolIdx, headCount) 区间即将被删除，必须连该 Assistant 一起移除，
            // 否则 LLM 会收到 tool_calls 但无对应 ToolResult，API 报错。
            int cutIdx = firstToolIdx;
            if (cutIdx > 0) {
                Msg preceding = messages.get(cutIdx - 1);
                if (preceding.role() == Role.ASSISTANT && hasToolCalls(List.of(preceding))) {
                    cutIdx--;
                }
            }

            List<Msg> truncated = new ArrayList<>(messages.subList(0, cutIdx));
            truncated.addAll(messages.subList(headCount, messages.size()));

            // 清除孤立 TOOL：截断后尾部可能残留 ToolResult 但其 ASSISTANT 已随截断删除，
            // 此类 TOOL 无前导 ASSISTANT(tc)，逐条丢弃保持消息序列语义完整。
            List<Msg> cleaned = new ArrayList<>(truncated.size());
            Msg lastNonTool = null;
            for (Msg msg : truncated) {
                if (msg.role() == Role.TOOL) {
                    if (lastNonTool == null
                            || lastNonTool.role() != Role.ASSISTANT
                            || !hasToolCalls(List.of(lastNonTool))) {
                        continue; // 孤儿 TOOL，丢弃
                    }
                }
                cleaned.add(msg);
                if (msg.role() != Role.TOOL) {
                    lastNonTool = msg;
                }
            }
            truncated = cleaned;

            log.info("ReAct 上下文压缩: {} → {} 条消息 (budget={}, estimated={})",
                    messages.size(), truncated.size(), contextBudget, estimatedTokens());

            return new ReActState(truncated, round, usage, lastToolName,
                    consecutiveSameTool, failedRoundCount, contextBudget, compressKeepRounds);
        }

        ReActState addReply(Msg reply) {
            var newMsgs = new ArrayList<Msg>(messages.size() + 1);
            newMsgs.addAll(messages);
            newMsgs.add(reply);
            return new ReActState(newMsgs, round, usage, lastToolName, consecutiveSameTool, failedRoundCount,
                    contextBudget, compressKeepRounds);
        }

        ReActState addToolResults(List<Content.ToolResult> results) {
            var newMsgs = new ArrayList<Msg>(messages.size() + results.size());
            newMsgs.addAll(messages);
            for (Content.ToolResult tr : results) {
                newMsgs.add(Msg.of(Role.TOOL, List.of(tr)));
            }
            var next = new ReActState(newMsgs, round, usage, lastToolName, consecutiveSameTool, failedRoundCount,
                    contextBudget, compressKeepRounds);
            // 每轮工具执行完检查上下文预算，超限自动压缩旧轮次
            return next.compressIfNeeded();
        }

        ReActState withUsage(TokenUsage newUsage) {
            return new ReActState(messages, round, newUsage, lastToolName, consecutiveSameTool, failedRoundCount,
                    contextBudget, compressKeepRounds);
        }

        ReActState withMessages(List<Msg> newMessages) {
            return new ReActState(newMessages, round, usage, lastToolName, consecutiveSameTool, failedRoundCount,
                    contextBudget, compressKeepRounds);
        }

        ReActState advanceRound(String toolName, int nextConsecutive, int nextFailed) {
            return new ReActState(messages, round + 1, usage, toolName, nextConsecutive, nextFailed,
                    contextBudget, compressKeepRounds);
        }
    }
}
