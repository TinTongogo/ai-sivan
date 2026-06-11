package com.icusu.sivan.agent.forest.mode;

import com.icusu.sivan.common.Mode;
import com.icusu.sivan.common.NodeStatus;
import com.icusu.sivan.domain.forest.ForestEvent;
import com.icusu.sivan.domain.forest.context.ExecutionContext;
import com.icusu.sivan.domain.forest.service.CheckpointHandler;
import com.icusu.sivan.domain.forest.service.Continuation;
import com.icusu.sivan.domain.forest.service.ModeStrategy;
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
        // 累计上下文：前一步的 DETAIL 事件内容累积并传递到后一步
        AtomicReference<String> accumulatedOutput = new AtomicReference<>("");

        return Flux.fromIterable(node.children())
                .concatMap(child -> {
                    if (!(child instanceof ExecutableNode execChild)) {
                        return Flux.empty();
                    }
                    if (execChild.status() != NodeStatus.PENDING) {
                        return Flux.empty();
                    }

                    // 将累积的上一步输出注入到子节点的元数据中
                    String prevOutput = accumulatedOutput.get();
                    if (!prevOutput.isEmpty() && child instanceof ContentNode cn) {
                        cn.metadata().put("accumulatedContext", prevOutput);
                    }

                    return executeChild(execChild, ctx, depth, next)
                            .doOnNext(event -> {
                                // 累积当前步的输出，供下一步使用
                                if (event.type() == ForestEvent.EventType.DETAIL) {
                                    accumulatedOutput.updateAndGet(s -> s + event.message());
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
