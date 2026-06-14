package com.icusu.sivan.agent.forest;

import com.icusu.sivan.common.NodeStatus;
import com.icusu.sivan.domain.forest.ForestEvent;
import com.icusu.sivan.domain.forest.context.ExecutionContext;
import com.icusu.sivan.domain.forest.port.Continuation;
import com.icusu.sivan.domain.forest.tree.ExecutableNode;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

/**
 * Continuation Mock — 用于测试 ModeStrategy 时替代真实的递归执行。
 * 不执行子节点，只记录调用顺序并将节点标记为 COMPLETED。
 */
public class MockContinuation implements Continuation {

    private final List<ExecutableNode> invoked = new ArrayList<>();

    @Override
    public Flux<ForestEvent> execute(ExecutableNode child, ExecutionContext ctx, int depth) {
        invoked.add(child);
        child.setStatus(NodeStatus.COMPLETED);
        return Flux.just(ForestEvent.lifecycle(child.nodeId(), null,
                ctx.accountId().toString(), ForestEvent.EventType.LIFECYCLE));
    }

    /** 返回按执行顺序排列的节点列表。 */
    public List<ExecutableNode> getInvoked() {
        return List.copyOf(invoked);
    }

    /** 清空记录。 */
    public void reset() {
        invoked.clear();
    }
}
