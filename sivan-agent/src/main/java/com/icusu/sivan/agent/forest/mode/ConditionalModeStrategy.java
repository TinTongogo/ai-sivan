package com.icusu.sivan.agent.forest.mode;

import com.icusu.sivan.common.Mode;
import com.icusu.sivan.common.NodeStatus;
import com.icusu.sivan.core.message.Content;
import com.icusu.sivan.core.message.Msg;
import com.icusu.sivan.core.message.Role;
import com.icusu.sivan.core.model.Model;
import com.icusu.sivan.core.model.ModelAccessor;
import com.icusu.sivan.domain.forest.ForestEvent;
import com.icusu.sivan.domain.forest.context.ExecutionContext;
import com.icusu.sivan.domain.forest.port.CheckpointHandler;
import com.icusu.sivan.domain.forest.port.Continuation;
import com.icusu.sivan.domain.forest.port.ModeStrategy;
import com.icusu.sivan.domain.forest.tree.ExecutableNode;
import com.icusu.sivan.domain.forest.tree.TreeNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * CONDITIONAL 编排策略 — LLM 驱动分支决策。
 * <p>
 * 使用 {@link ModelAccessor} 获取模型，每阶段执行后由 LLM 判断是否继续。
 */
@Component
public class ConditionalModeStrategy implements ModeStrategy {

    private static final Logger log = LoggerFactory.getLogger(ConditionalModeStrategy.class);
    private static final Duration DECIDE_TIMEOUT = Duration.ofSeconds(10);

    private final ModelAccessor modelAccessor;
    private final CheckpointHandler checkpointHandler;

    public ConditionalModeStrategy(ModelAccessor modelAccessor, CheckpointHandler checkpointHandler) {
        this.modelAccessor = modelAccessor;
        this.checkpointHandler = checkpointHandler;
    }

    @Override
    public Mode supportedMode() {
        return Mode.CONDITIONAL;
    }

    @Override
    public Flux<ForestEvent> execute(
            ExecutableNode node,
            ExecutionContext ctx,
            int depth,
            Continuation next
    ) {
        List<ExecutableNode> pending = node.children().stream()
                .filter(TreeNode::isExecutable)
                .map(c -> (ExecutableNode) c)
                .filter(c -> c.status() == NodeStatus.PENDING)
                .toList();

        if (pending.isEmpty()) {
            return Flux.empty();
        }

        return executePhases(node, ctx, depth, next, pending, 0, "");
    }

    private Flux<ForestEvent> executePhases(
            ExecutableNode node, ExecutionContext ctx, int depth,
            Continuation next, List<ExecutableNode> pending, int index,
            String accumulatedOutput
    ) {
        if (index >= pending.size()) {
            return Flux.empty();
        }

        ExecutableNode phase = pending.get(index);

        // 将累积的上步输出注入到当前阶段节点的元数据中
        if (!accumulatedOutput.isEmpty() && phase instanceof com.icusu.sivan.domain.forest.tree.ContentNode cn) {
            cn.metadata().put("accumulatedContext", accumulatedOutput);
        }

        Flux<ForestEvent> phaseExecution = checkpointHandler.isHitlRequired(phase)
                ? checkpointHandler.check(phase, ctx)
                    .flatMapMany(pause -> {
                        if (pause.isRejected()) {
                            phase.setStatus(NodeStatus.CANCELLED);
                            return Flux.just(ForestEvent.lifecycle(phase.nodeId(), null,
                                    ctx.accountId().toString(), ForestEvent.EventType.NODE_END));
                        }
                        return next.execute(phase, ctx, depth + 1);
                    })
                : next.execute(phase, ctx, depth + 1);

        StringBuilder[] phaseAcc = {new StringBuilder(accumulatedOutput)};
        return phaseExecution
                .doOnNext(event -> {
                    if (event.type() == ForestEvent.EventType.DETAIL) {
                        phaseAcc[0].append(event.message());
                    }
                })
                .collectList()
                .flatMapMany(events -> {
                    String phaseOutput = events.stream()
                            .map(ForestEvent::message)
                            .filter(m -> m != null && !m.isEmpty())
                            .collect(Collectors.joining(" "));

                    return decide(phase, phaseOutput, ctx)
                            .flatMapMany(decision -> {
                                if (!decision) {
                                    log.info("[CONDITIONAL] LLM 决定停止，{} 个阶段已完成", index + 1);
                                    // 发射 branch_decision 事件
                                    List<String> skipped = pending.subList(index + 1, pending.size()).stream()
                                            .map(n -> n.nodeId().substring(0, 8))
                                            .toList();
                                    return Flux.concat(
                                            Flux.just(ForestEvent.branchDecision(phase.nodeId(), null,
                                                    ctx.accountId().toString(),
                                                    "{\"chosen\":\"完成\",\"skipped\":" + toJsonArray(skipped)
                                                            + ",\"reason\":\"LLM 判定目标已达成\"}")),
                                            Flux.fromIterable(events)
                                    );
                                }
                                // 继续下一阶段，发射 branch_decision 事件
                                List<String> rest = pending.subList(index + 1, pending.size()).stream()
                                        .map(n -> n.nodeId().substring(0, 8))
                                        .toList();
                                return Flux.concat(
                                        Flux.just(ForestEvent.branchDecision(phase.nodeId(), null,
                                                ctx.accountId().toString(),
                                                "{\"chosen\":\"" + phase.nodeId().substring(0, 8)
                                                        + "\",\"skipped\":" + toJsonArray(rest)
                                                        + ",\"reason\":\"继续执行下一阶段\"}")),
                                        Flux.fromIterable(events),
                                        executePhases(node, ctx, depth, next, pending, index + 1, phaseAcc[0].toString())
                                );
                            });
                })
                .timeout(DECIDE_TIMEOUT)
                .onErrorResume(TimeoutException.class, e -> {
                    log.warn("[CONDITIONAL] 决策超时，继续下一阶段");
                    return executePhases(node, ctx, depth, next, pending, index + 1, phaseAcc[0].toString());
                });
    }

    private Mono<Boolean> decide(ExecutableNode phase, String phaseOutput, ExecutionContext ctx) {
        if (phaseOutput.isBlank()) {
            return Mono.just(true);
        }

        UUID accountId = ctx.accountId();
        Model model;
        try {
            model = modelAccessor.getDefaultModel(accountId);
        } catch (Exception e) {
            log.warn("[CONDITIONAL] 获取模型失败: {}", e.getMessage());
            return Mono.just(true);
        }

        List<Msg> messages = List.of(
                Msg.of(Role.SYSTEM, List.of(new Content.Text("""
                        你是任务编排决策系统。根据上一阶段的执行结果，决定是否继续执行下一阶段。
                        回复格式：只返回一个单词，YES 或 NO。
                        YES = 执行结果达到预期，继续下一阶段
                        NO = 执行结果已满足目标，跳过剩余阶段直接完成"""))),
                Msg.of(Role.USER, List.of(new Content.Text("上一阶段结果: " + truncate(phaseOutput, 500))))
        );

        return model.chat(messages, List.of(), Model.ModelParams.defaults().withTemperature(0.3))
                .map(response -> {
                    String text = response.msg().text().trim().toUpperCase();
                    log.info("[CONDITIONAL] LLM 决策: {} (来自: {})", text, phase.nodeId().substring(0, 8));
                    return !text.contains("NO");
                })
                .timeout(DECIDE_TIMEOUT)
                .onErrorReturn(true);
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private static String toJsonArray(List<String> items) {
        return items.stream()
                .collect(java.util.stream.Collectors.joining("\",\"", "[\"", "\"]"));
    }

    private static String phaseLabel(ExecutableNode n) {
        String text = n instanceof com.icusu.sivan.domain.forest.tree.ContentNode cn ? cn.content() : "";
        return text.length() > 30 ? text.substring(0, 30) + "..." : text;
    }
}
