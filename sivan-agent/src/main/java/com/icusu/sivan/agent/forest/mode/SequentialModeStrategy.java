package com.icusu.sivan.agent.forest.mode;

import com.icusu.sivan.common.Mode;
import com.icusu.sivan.common.NodeStatus;
import com.icusu.sivan.domain.forest.ForestEvent;
import com.icusu.sivan.domain.forest.context.ExecutionContext;
import com.icusu.sivan.domain.forest.port.CheckpointHandler;
import com.icusu.sivan.domain.forest.port.Continuation;
import com.icusu.sivan.domain.forest.port.ModeStrategy;
import com.icusu.sivan.domain.forest.tree.ContentNode;
import com.icusu.sivan.domain.forest.tree.ExecutableNode;
import com.icusu.sivan.domain.forest.tree.TreeNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * SEQUENTIAL 编排策略 — 按子节点顺序逐一执行，前一步的输出累积传递到后一步。
 * <p>
 * HITL: 每个子节点执行前检查 {@code hitl} 标记，需审批时暂停等待。
 */
@Component
@Slf4j
public class SequentialModeStrategy implements ModeStrategy {

    private final CheckpointHandler checkpointHandler;

    public SequentialModeStrategy(CheckpointHandler checkpointHandler) {
        this.checkpointHandler = checkpointHandler;
    }

    @Override
    public Mode supportedMode() {
        return Mode.SEQUENTIAL;
    }

    @Override
    public Flux<ForestEvent> execute(
            ExecutableNode node,
            ExecutionContext ctx,
            int depth,
            Continuation next
    ) {
        // 累计上下文：前一步的输出累积并传递到后一步，带步骤标签以便汇总步骤引用
        final int[] stepIndex = {0};
        AtomicReference<String> accumulatedOutput = new AtomicReference<>("");

        return Flux.fromIterable(node.children())
                .concatMap(child -> {
                    if (!(child instanceof ExecutableNode execChild)) {
                        return Flux.empty();
                    }
                    if (execChild.status() != NodeStatus.PENDING) {
                        return Flux.empty();
                    }

                    if (child instanceof ContentNode cn) {
                        // 标记为 SEQUENTIAL 子任务：不携带完整对话历史
                        child.putMetadata("_isSequentialSubtask", "true");

                        // 将累积的上一步输出（带步骤标签）注入子节点元数据
                        String prevOutput = accumulatedOutput.get();
                        if (!prevOutput.isEmpty()) {
                            child.putMetadata("accumulatedContext", prevOutput);
                        }
                    }

                    stepIndex[0]++;
                    final int currentStep = stepIndex[0];
                    int totalSteps = node.children().size();

                    return executeChild(execChild, ctx, depth, next)
                            .doOnNext(event -> {
                                if (event.type() == ForestEvent.EventType.DETAIL) {
                                    // 用步骤标签包裹输出，方便后续步骤引用
                                    String labeled = "步骤" + currentStep + "/" + totalSteps + "：" + event.message();
                                    accumulatedOutput.updateAndGet(s -> s + (s.isEmpty() ? "" : "\n") + labeled);
                                }
                            });
                });
    }

    private Flux<ForestEvent> executeChild(ExecutableNode child, ExecutionContext ctx, int depth, Continuation next) {
        if (!checkpointHandler.isHitlRequired(child)) {
            return next.execute(child, ctx, depth + 1);
        }
        return checkpointHandler.check(child, ctx)
                .timeout(Duration.ofMinutes(30))
                .onErrorResume(TimeoutException.class, e -> {
                    log.warn("[Sequential] HITL 审批超时，自动继续: nodeId={}", child.nodeId());
                    return reactor.core.publisher.Mono.empty(); // 超时视为自动通过
                })
                .flatMapMany(pause -> {
                    if (pause.isRejected()) {
                        child.setStatus(NodeStatus.CANCELLED);
                        return Flux.just(ForestEvent.lifecycle(child.nodeId(), null,
                                ctx.accountId().toString(), ForestEvent.EventType.LIFECYCLE));
                    }
                    return next.execute(child, ctx, depth + 1);
                });
    }
}
