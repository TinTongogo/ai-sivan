package com.icusu.sivan.agent.forest.mode;

import com.icusu.sivan.common.Mode;
import com.icusu.sivan.common.NodeStatus;
import com.icusu.sivan.domain.forest.ForestEvent;
import com.icusu.sivan.domain.forest.context.ExecutionContext;
import com.icusu.sivan.domain.forest.service.CheckpointHandler;
import com.icusu.sivan.domain.forest.service.Continuation;
import com.icusu.sivan.domain.forest.service.ModeStrategy;
import com.icusu.sivan.domain.forest.tree.ExecutableNode;
import com.icusu.sivan.domain.forest.tree.SynthesisNode;
import com.icusu.sivan.domain.forest.tree.TreeNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.stream.Collectors;

/**
 * CONSENSUS 编排策略 — 并行执行 + 委托 SynthesisLeafExecutor 合成。
 * <p>
 * 先并行执行所有常规子节点，再将合并输出写入 SynthesisNode.content，
 * 通过 {@link Continuation} 委托执行 SynthesisNode（走 {@code LeafExecutorRegistry} → {@code SynthesisLeafExecutor}）。
 */
@Component
public class ConsensusModeStrategy implements ModeStrategy {

    private static final Logger log = LoggerFactory.getLogger(ConsensusModeStrategy.class);

    private final CheckpointHandler checkpointHandler;

    public ConsensusModeStrategy(CheckpointHandler checkpointHandler) {
        this.checkpointHandler = checkpointHandler;
    }

    @Override
    public Mode supportedMode() {
        return Mode.CONSENSUS;
    }

    @Override
    public Flux<ForestEvent> execute(
            ExecutableNode node,
            ExecutionContext ctx,
            int depth,
            Continuation next
    ) {
        List<TreeNode> children = node.children();
        List<ExecutableNode> all = children.stream()
                .filter(TreeNode::isExecutable)
                .map(c -> (ExecutableNode) c)
                .filter(c -> c.status() == NodeStatus.PENDING)
                .toList();

        if (all.isEmpty()) {
            return Flux.empty();
        }

        List<ExecutableNode> regularNodes;
        ExecutableNode last = all.get(all.size() - 1);
        boolean hasSynthesis = last instanceof SynthesisNode;
        final ExecutableNode synthesisNode = hasSynthesis ? last : null;

        if (hasSynthesis) {
            regularNodes = all.subList(0, all.size() - 1);
        } else {
            regularNodes = all;
        }

        log.info("[CONSENSUS] {} 个并行节点 + {}", regularNodes.size(),
                hasSynthesis ? "SynthesisNode 合成" : "无合成");

        Flux<ForestEvent> parallel = Flux.fromIterable(regularNodes)
                .flatMap(child ->
                        next.execute(child, ctx, depth + 1)
                                .onErrorResume(e ->
                                        Flux.just(ForestEvent.error(child.nodeId(), null,
                                                ctx.accountId().toString(),
                                                "节点执行失败: " + e.getMessage())))
                );

        if (!hasSynthesis) {
            return parallel;
        }

        return parallel.collectList()
                .flatMapMany(allEvents -> {
                    String combinedOutput = allEvents.stream()
                            .map(ForestEvent::message)
                            .filter(m -> m != null && !m.isEmpty())
                            .collect(Collectors.joining("\n---\n"));

                    if (synthesisNode instanceof SynthesisNode sn) {
                        sn.content(combinedOutput);
                    }

                    if (checkpointHandler.isHitlRequired(node)) {
                        return checkpointHandler.check(node, ctx)
                                .flatMapMany(pause -> {
                                    if (pause.isRejected()) {
                                        node.setStatus(NodeStatus.CANCELLED);
                                        return Flux.just(ForestEvent.lifecycle(
                                                node.nodeId(), null, ctx.accountId().toString(),
                                                ForestEvent.EventType.LIFECYCLE));
                                    }
                                    return next.execute(synthesisNode, ctx, depth + 1);
                                });
                    }
                    return next.execute(synthesisNode, ctx, depth + 1);
                })
                .onErrorResume(e -> {
                    log.error("[CONSENSUS] 合成失败", e);
                    return next.execute(synthesisNode, ctx, depth + 1);
                });
    }
}
