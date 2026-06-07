package com.icusu.sivan.orch.executor;

import com.icusu.sivan.common.context.Account;
import com.icusu.sivan.core.model.TokenUsage;
import com.icusu.sivan.core.tool.ToolSpec;
import com.icusu.sivan.orch.intent.LocalIntentClassifier;
import com.icusu.sivan.orch.strategy.OrchestrationStrategy;
import com.icusu.sivan.orch.topology.ExecutionPathResolver;
import com.icusu.sivan.orch.topology.ExecutionPathResult;
import com.icusu.sivan.agent.model.ModelRouter;
import com.icusu.sivan.agent.routing.RoutingDecisionRecorder;
import com.icusu.sivan.agent.routing.RoutingDecisionRecorder.RecordRequest;
import com.icusu.sivan.agent.service.TokenUsageRecorder;
import com.icusu.sivan.common.enums.Intent;
import com.icusu.sivan.common.enums.TokenSource;
import com.icusu.sivan.core.message.Msg;
import com.icusu.sivan.core.message.Role;
import com.icusu.sivan.core.model.Model.ModelParams;
import com.icusu.sivan.domain.shared.vo.TokenContext;
import com.icusu.sivan.domain.task.ExecutionShape;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 意图分类 + 编排分发入口。
 * <p>
 * 三级分类流水线：
 * <ol>
 *   <li>本能模板匹配（ExecutionPathResolver）→ 命中直接返回</li>
 *   <li>本地语义匹配（LocalIntentClassifier, embedding）→ 高置信度直接返回</li>
 *   <li>LLM classify → 结果反馈给本地分类器持续改进</li>
 * </ol>
 * 分发到对应的 {@link OrchestrationStrategy}，覆盖 CHAT / SINGLE_AGENT / SQUAD 三种策略。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SquadOrchestrator {

    private final OrchestrationDispatcher dispatcher;
    private final ModelRouter modelRouter;
    private final ExecutionPathResolver executionPathResolver;
    private final RoutingDecisionRecorder routingDecisionRecorder;
    private final TokenUsageRecorder tokenUsageRecorder;
    private final LocalIntentClassifier localIntentClassifier;

    /**
     * 分类意图：先本地语义匹配（embedding），高置信度直接返回；
     * 低置信度交由 LLM 分类，结果反馈改进本地分类器。
     */
    public Mono<Intent> classify(String taskDescription, UUID accountId, UUID projectId) {
        return classify(taskDescription, accountId, projectId, null);
    }

    /**
     * 分类意图（带上下文感知）。
     *
     * @param assistantContext 上一轮助手回复（可为 null），用于本地语义分类时的上下文增强
     */
    public Mono<Intent> classify(String taskDescription, UUID accountId, UUID projectId,
                                  String assistantContext) {
        // 第一级：本地语义匹配
        Intent localResult = localIntentClassifier.classify(taskDescription, assistantContext);
        if (localResult != null) {
            log.debug("意图快速判断: {} (本地语义), text='{}'", localResult, truncate(taskDescription));
            return Mono.just(localResult);
        }

        // 第二级：LLM classify
        String modelId = modelRouter.getDefaultModel(accountId).modelId();
        TokenContext tokenCtx = TokenContext.builder()
                .accountId(accountId)
                .projectId(projectId)
                .source(TokenSource.ROUTING)
                .build();

        return modelRouter.getDefaultModel(accountId)
                .chat(List.of(
                                Msg.of(Role.SYSTEM, com.icusu.sivan.agent.prompt.ChatPrompts.INTENT_CLASSIFY_SYSTEM.content()),
                                Msg.of(Role.USER, taskDescription)),
                        ModelParams.defaults())
                .doOnNext(response -> {
                    if (response.usage() != null && response.usage().totalTokens() > 0) {
                        tokenUsageRecorder.saveUsage(response.usage(), tokenCtx, modelId);
                    }
                })
                .map(response -> {
                    Intent intent = parseIntent(response.msg().text());
                    // LLM 结果反馈改进本地分类器
                    localIntentClassifier.recordLlmClassification(taskDescription, intent);
                    return intent;
                })
                .onErrorResume(e -> {
                    log.warn("意图分类 LLM 调用失败，降级为 CHAT: {}", e.getMessage());
                    return Mono.just(Intent.CHAT);
                });
    }

    /**
     * 流式分类意图：先本地语义匹配，低置信度时交由 LLM 推理并反馈。
     */
    public Mono<Intent> classifyStream(String taskDescription, UUID accountId, UUID projectId,
                                        Consumer<String> tokenCallback) {
        return classifyStream(taskDescription, accountId, projectId, tokenCallback, null);
    }

    /**
     * 流式分类意图（带上下文感知）。
     *
     * @param assistantContext 上一轮助手回复（可为 null），用于本地语义分类时的上下文增强
     */
    public Mono<Intent> classifyStream(String taskDescription, UUID accountId, UUID projectId,
                                        Consumer<String> tokenCallback, String assistantContext) {
        // 第一级：本地语义匹配
        Intent localResult = localIntentClassifier.classify(taskDescription, assistantContext);
        if (localResult != null) {
            log.debug("意图快速判断: {} (本地语义), text='{}'", localResult, truncate(taskDescription));
            return Mono.just(localResult);
        }

        // 第二级：LLM classifyStream
        String modelId = modelRouter.getDefaultModel(accountId).modelId();
        TokenContext tokenCtx = TokenContext.builder()
                .accountId(accountId)
                .projectId(projectId)
                .source(TokenSource.ROUTING)
                .build();

        return modelRouter.getDefaultModel(accountId)
                .stream(
                        List.of(
                                Msg.of(Role.SYSTEM, com.icusu.sivan.agent.prompt.ChatPrompts.INTENT_CLASSIFY_SYSTEM.content()),
                                Msg.of(Role.USER, taskDescription)),
                        ModelParams.defaults())
                .doOnNext(chunk -> {
                    String delta = chunk.content();
                    if (delta != null && !delta.isEmpty()) {
                        tokenCallback.accept(delta);
                    }
                })
                .reduce(new TokenAccumulator(), (acc, chunk) -> acc.append(chunk.content(), chunk.usage()))
                .map(acc -> {
                    if (acc.lastUsage != null && acc.lastUsage.totalTokens() > 0) {
                        tokenUsageRecorder.saveUsage(acc.lastUsage, tokenCtx, modelId);
                    }
                    Intent intent = parseIntent(acc.text.toString());
                    // LLM 结果反馈改进本地分类器
                    localIntentClassifier.recordLlmClassification(taskDescription, intent);
                    return intent;
                })
                .onErrorResume(e -> {
                    log.warn("意图分类 LLM 流式调用失败，降级为 CHAT: {}", e.getMessage());
                    return Mono.just(Intent.CHAT);
                });
    }

    /** 流式分类累加器：收集文本和最后一个 chunk 的 usage。 */
    private static class TokenAccumulator {
        final StringBuilder text = new StringBuilder();
        TokenUsage lastUsage;

        TokenAccumulator append(String content, TokenUsage usage) {
            text.append(content);
            if (usage != null) lastUsage = usage;
            return this;
        }
    }

    /** 从 LLM 分类响应文本中解析 Intent。优先匹配"结果："行后的标签。 */
    private static Intent parseIntent(String text) {
        if (text == null || text.isBlank()) return Intent.CHAT;
        String upper = text.trim().toUpperCase();
        int idx = upper.lastIndexOf("结果：");
        if (idx >= 0) {
            String after = upper.substring(idx + 3).trim();
            if (after.contains("SINGLE_AGENT")) return Intent.SINGLE_AGENT;
            if (after.contains("SQUAD")) return Intent.SQUAD;
            if (after.contains("CHAT")) return Intent.CHAT;
        }
        if (upper.contains("SINGLE_AGENT")) return Intent.SINGLE_AGENT;
        if (upper.contains("SQUAD")) return Intent.SQUAD;
        return Intent.CHAT;
    }

    /**
     * 解析意图：先尝试本能模板匹配，命中且未触发探索时跳过 LLM classify。
     * 模板未命中或触发探索时降级到 {@link #classify}。
     */
    public Mono<Intent> resolveIntent(String taskDescription, UUID accountId,
                                       UUID projectId, UUID conversationId) {
        ExecutionPathResult result = executionPathResolver.resolve(taskDescription, accountId);
        if (result.shouldSkipClassify()) {
            Intent intent = toIntent(result.executionPath().shape());
            log.info("意图解析统计: 模板命中直接使用, shape={}, patternId={}",
                    intent, result.patternId());
            try {
                routingDecisionRecorder.record(RecordRequest.simple(
                        accountId, projectId, conversationId, taskDescription,
                        "TEMPLATE_MATCH", intent.name(), true,
                        "本能模板命中: shape=" + intent + ", patternId=" + result.patternId(),
                        Map.of("patternId", result.patternId() != null ? result.patternId().toString() : "")));
            } catch (Exception e) {
                log.warn("记录模板匹配路由决策失败: {}", e.getMessage());
            }
            return Mono.just(intent);
        }
        if (result.fromTemplate()) {
            log.info("意图解析统计: 模板命中但触发探索, 降级 LLM classify, patternId={}", result.patternId());
        } else {
            log.info("意图解析统计: 模板未命中, 使用 LLM classify");
        }
        return classify(taskDescription, accountId, projectId);
    }

    /**
     * 解析意图（流式版）：同上但通过 tokenCallback 逐字发射 LLM 推理过程。
     */
    public Mono<Intent> resolveIntent(String taskDescription, UUID accountId,
                                       UUID projectId, UUID conversationId,
                                       Consumer<String> classifyTokenCallback) {
        return resolveIntent(taskDescription, accountId, projectId, conversationId,
                classifyTokenCallback, null);
    }

    /**
     * 解析意图（流式版，带上下文感知）。
     *
     * @param assistantContext 上一轮助手回复内容（可为 null），用于本地语义分类时的上下文增强
     */
    public Mono<Intent> resolveIntent(String taskDescription, UUID accountId,
                                       UUID projectId, UUID conversationId,
                                       Consumer<String> classifyTokenCallback,
                                       String assistantContext) {
        ExecutionPathResult result = executionPathResolver.resolve(taskDescription, accountId);
        if (result.shouldSkipClassify()) {
            Intent intent = toIntent(result.executionPath().shape());
            log.info("意图解析统计: 模板命中直接使用, shape={}, patternId={}",
                    intent, result.patternId());
            try {
                routingDecisionRecorder.record(RecordRequest.simple(
                        accountId, projectId, conversationId, taskDescription,
                        "TEMPLATE_MATCH", intent.name(), true,
                        "本能模板命中: shape=" + intent + ", patternId=" + result.patternId(),
                        Map.of("patternId", result.patternId() != null ? result.patternId().toString() : "")));
            } catch (Exception e) {
                log.warn("记录模板匹配路由决策失败: {}", e.getMessage());
            }
            return Mono.just(intent);
        }
        if (result.fromTemplate()) {
            log.info("意图解析统计: 模板命中但触发探索, 降级 LLM classifyStream, patternId={}", result.patternId());
        } else {
            log.info("意图解析统计: 模板未命中, 使用 LLM classifyStream");
        }
        return classifyStream(taskDescription, accountId, projectId, classifyTokenCallback, assistantContext);
    }

    /**
     * ExecutionShape → Intent 映射。
     */
    private static Intent toIntent(ExecutionShape shape) {
        return switch (shape) {
            case CHAT -> Intent.CHAT;
            case SINGLE_AGENT -> Intent.SINGLE_AGENT;
            case SQUAD -> Intent.SQUAD;
        };
    }

    public Flux<OrchestrationEvent> orchestrateStream(Intent intent, String taskDescription, UUID accountId,
                                                      String historyContext, UUID conversationId,
                                                      Account account,
                                                      String targetAgent,
                                                      List<ToolSpec> mcpTools,
                                                      UUID providerId, List<Msg> chatMsgs, boolean stream,
                                                      String projectHint, String fileRootPath, boolean archived) {
        var ctx = new OrchestrationStrategy.OrchestrationContext(
                taskDescription, accountId, historyContext, conversationId, account, targetAgent, mcpTools,
                providerId, chatMsgs, stream, projectHint, fileRootPath, archived);
        return dispatcher.dispatch(intent, ctx).subscribeOn(Schedulers.boundedElastic());
    }

    private static String truncate(String s) {
        return s != null && s.length() > 60 ? s.substring(0, 60) + "..." : s;
    }
}
