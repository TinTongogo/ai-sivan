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
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

        // 多 Agent 协作：为每个子节点注入队友信息
        if (ready.size() > 1) {
            for (ExecutableNode child : ready) {
                List<Map<String, String>> peers = new ArrayList<>();
                for (ExecutableNode sibling : ready) {
                    if (sibling != child && sibling instanceof ContentNode sc) {
                        Map<String, String> peer = new HashMap<>();
                        peer.put("agentId", sibling.nodeId());
                        peer.put("task", sc.content());
                        // 传递队友的 Agent 名称（如有），供 AgentLeafExecutor 查询能力
                        Object peerAgent = sc.metadata().get("agentName");
                        if (peerAgent instanceof String s && !s.isBlank()) {
                            peer.put("agentName", s);
                        }
                        peers.add(peer);
                    }
                }
                if (child instanceof ContentNode cn) {
                    cn.metadata().put("peers", peers);
                }
            }
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
