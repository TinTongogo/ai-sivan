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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * HIERARCHICAL 编排策略 — 规划→执行。
 * <p>
 * HITL: 规划完成后、执行阶段前暂停等待审批。
 */
@Component
public class HierarchicalModeStrategy implements ModeStrategy {

    private static final Logger log = LoggerFactory.getLogger(HierarchicalModeStrategy.class);

    private final CheckpointHandler checkpointHandler;

    public HierarchicalModeStrategy(CheckpointHandler checkpointHandler) {
        this.checkpointHandler = checkpointHandler;
    }

    @Override
    public Mode supportedMode() {
        return Mode.HIERARCHICAL;
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

        ExecutableNode planner = pending.get(0);
        List<ExecutableNode> executors = pending.size() > 1
                ? pending.subList(1, pending.size())
                : List.of();

        log.info("[HIERARCHICAL] 规划器: {}, 执行阶段: {} 个", planner.nodeId().substring(0, 8), executors.size());

        StringBuilder[] planAcc = {new StringBuilder()};
        return next.execute(planner, ctx, depth + 1)
                .doOnNext(event -> {
                    if (event.type() == ForestEvent.EventType.DETAIL) {
                        planAcc[0].append(event.message());
                    }
                })
                .collectList()
                .flatMapMany(planEvents -> {
                    String planOutput = planAcc[0].toString();
                    if (!planOutput.isEmpty() && !executors.isEmpty() && executors.get(0) instanceof ContentNode cn) {
                        cn.metadata().put("accumulatedContext", planOutput);
                    }
                    Flux<ForestEvent> execFlux = executeExecutors(executors, ctx, depth, next, planOutput);
                    if (checkpointHandler.isHitlRequired(node)) {
                        return checkpointHandler.check(node, ctx)
                                .flatMapMany(pause -> {
                                    if (pause.isRejected()) {
                                        node.setStatus(NodeStatus.CANCELLED);
                                        return Flux.fromIterable(planEvents);
                                    }
                                    return Flux.fromIterable(planEvents).concatWith(execFlux);
                                });
                    }
                    return Flux.fromIterable(planEvents).concatWith(execFlux);
                });
    }

    private Flux<ForestEvent> executeExecutors(
            List<ExecutableNode> executors,
            ExecutionContext ctx,
            int depth,
            Continuation next,
            String initialContext
    ) {
        if (executors.isEmpty()) {
            return Flux.empty();
        }

        AtomicReference<String> accumulatedOutput = new AtomicReference<>(initialContext);
        return Flux.fromIterable(executors)
                .concatMap(child -> {
                    // 检查前一步是否失败，失败则跳过剩余步骤
                    if (child.status() == NodeStatus.FAILED) {
                        log.warn("[HIERARCHICAL] 前序步骤失败，跳过: nodeId={}", child.nodeId().substring(0, 8));
                        return Flux.empty();
                    }

                    String prevOutput = accumulatedOutput.get();
                    if (!prevOutput.isEmpty() && child instanceof com.icusu.sivan.domain.forest.tree.ContentNode cn) {
                        cn.metadata().put("accumulatedContext", prevOutput);
                    }
                    return next.execute(child, ctx, depth + 1)
                            .doOnNext(event -> {
                                if (event.type() == ForestEvent.EventType.DETAIL) {
                                    accumulatedOutput.updateAndGet(s -> s + event.message());
                                }
                            });
                });
    }
}
