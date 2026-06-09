package com.icusu.sivan.agent.forest.mode;

import com.icusu.sivan.common.Mode;
import com.icusu.sivan.common.NodeStatus;
import com.icusu.sivan.domain.forest.ForestEvent;
import com.icusu.sivan.domain.forest.context.ExecutionContext;
import com.icusu.sivan.domain.forest.service.CheckpointHandler;
import com.icusu.sivan.domain.forest.service.Continuation;
import com.icusu.sivan.domain.forest.service.ModeStrategy;
import com.icusu.sivan.domain.forest.tree.ExecutableNode;
import com.icusu.sivan.domain.forest.tree.TreeNode;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * PARALLEL 编排策略 — 全部子节点并发执行。
 * <p>
 * HITL: 各子节点独立检查 {@code hitl} 标记，一个节点的审批暂停不影响其他节点。
 */
@Component
public class ParallelModeStrategy implements ModeStrategy {

    private final CheckpointHandler checkpointHandler;

    public ParallelModeStrategy(CheckpointHandler checkpointHandler) {
        this.checkpointHandler = checkpointHandler;
    }

    @Override
    public Mode supportedMode() {
        return Mode.PARALLEL;
    }

    @Override
    public Flux<ForestEvent> execute(
            ExecutableNode node,
            ExecutionContext ctx,
            int depth,
            Continuation next
    ) {
        List<ExecutableNode> ready = node.children().stream()
                .filter(TreeNode::isExecutable)
                .map(c -> (ExecutableNode) c)
                .filter(c -> c.status() == NodeStatus.PENDING)
                .toList();

        if (ready.isEmpty()) {
            return Flux.empty();
        }

        return Flux.fromIterable(ready)
                .flatMap(child ->
                        executeChild(child, ctx, depth, next)
                                .onErrorResume(e ->
                                        Flux.just(ForestEvent.error(child.nodeId(), null,
                                                ctx.accountId().toString(),
                                                "节点执行失败: " + e.getMessage()))
                                )
                );
    }

    private Flux<ForestEvent> executeChild(ExecutableNode child, ExecutionContext ctx, int depth, Continuation next) {
        if (!checkpointHandler.isHitlRequired(child)) {
            return next.execute(child, ctx, depth + 1);
        }
        return checkpointHandler.check(child, ctx)
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
