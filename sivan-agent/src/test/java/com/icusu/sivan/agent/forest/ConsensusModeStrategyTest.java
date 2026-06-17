package com.icusu.sivan.agent.forest;

import com.icusu.sivan.agent.forest.mode.ConsensusModeStrategy;
import com.icusu.sivan.common.Mode;
import com.icusu.sivan.common.NodeStatus;
import com.icusu.sivan.domain.forest.ForestEvent;
import com.icusu.sivan.domain.forest.context.ExecutionContext;
import com.icusu.sivan.domain.forest.port.CheckpointHandler;
import com.icusu.sivan.domain.forest.port.Continuation;
import com.icusu.sivan.domain.forest.tree.node.InnerGoalNode;
import com.icusu.sivan.domain.forest.tree.node.SynthesisNode;
import com.icusu.sivan.domain.forest.tree.node.TaskNode;
import com.icusu.sivan.domain.forest.vo.PauseRequest;
import com.icusu.sivan.domain.forest.tree.*;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ConsensusModeStrategy} 单元测试。
 */
class ConsensusModeStrategyTest {

    private final CheckpointHandler noopCheckpoint = new CheckpointHandler() {
        @Override
        public Mono<PauseRequest> check(ExecutableNode node, ExecutionContext ctx) {
            return Mono.empty();
        }

        @Override
        public void approve(String nodeId, String accountId) {}

        @Override
        public void reject(String nodeId, String accountId, String reason) {}
    };

    private final ConsensusModeStrategy strategy = new ConsensusModeStrategy(noopCheckpoint);
    private final ExecutionContext ctx = ExecutionContext.create(UUID.randomUUID());

    @Test
    void noChildrenReturnsEmpty() {
        var root = new InnerGoalNode(Mode.CONSENSUS, List.of());
        ContinuationMock cont = new ContinuationMock();

        Flux<ForestEvent> result = strategy.execute(root, ctx, 0, cont);
        StepVerifier.create(result).verifyComplete();
    }

    @Test
    void onlySynthesisChildExecutesSynthesis() {
        var syn = new SynthesisNode();
        var root = new InnerGoalNode(Mode.CONSENSUS, List.of(syn));

        ContinuationMock cont = new ContinuationMock();

        Flux<ForestEvent> result = strategy.execute(root, ctx, 0, cont);
        StepVerifier.create(result)
                .expectNextMatches(e -> e.nodeId().equals(syn.nodeId()))
                .verifyComplete();

        assertEquals(1, cont.executedNodes.size());
        assertEquals(syn.nodeId(), cont.executedNodes.get(0).nodeId());
    }

    @Test
    void regularChildrenExecutedInParallelThenSynthesis() {
        var t1 = new TaskNode("task-1");
        var t2 = new TaskNode("task-2");
        var syn = new SynthesisNode();
        var root = new InnerGoalNode(Mode.CONSENSUS, List.of(t1, t2, syn));

        ContinuationMock cont = new ContinuationMock();

        Flux<ForestEvent> result = strategy.execute(root, ctx, 0, cont);
        StepVerifier.create(result)
                .expectNextMatches(e -> e.nodeId().equals(syn.nodeId()))
                .verifyComplete();

        assertEquals(3, cont.executedNodes.size());
        assertEquals(syn.nodeId(), cont.executedNodes.get(2).nodeId());
    }

    @Test
    void completedChildrenSkipped() {
        var t1 = new TaskNode("done");
        t1.setStatus(NodeStatus.COMPLETED);
        var t2 = new TaskNode("pending");
        var root = new InnerGoalNode(Mode.CONSENSUS, List.of(t1, t2));

        ContinuationMock cont = new ContinuationMock();

        strategy.execute(root, ctx, 0, cont).blockLast();
        assertEquals(1, cont.executedNodes.size());
        assertEquals(t2.nodeId(), cont.executedNodes.get(0).nodeId());
    }

    @Test
    void synthesisReceivesCombinedOutput() {
        var t1 = new TaskNode("task-1");
        var syn = new SynthesisNode("syn-id", "", NodeStatus.PENDING);
        var root = new InnerGoalNode(Mode.CONSENSUS, List.of(t1, syn));

        Continuation cont = (child, c, d) -> {
            if (child instanceof SynthesisNode sn) {
                assertFalse(sn.content().isEmpty());
                assertTrue(sn.content().contains("output-A"));
            }
            return Flux.just(ForestEvent.detail(child.nodeId(), null, c.accountId().toString(),
                    child == t1 ? "output-A" : "combined"));
        };

        strategy.execute(root, ctx, 0, cont).blockLast();
    }

    @Test
    void allPendingChildrenParallelExecution() {
        var tasks = new ArrayList<TaskNode>();
        for (int i = 0; i < 5; i++) {
            tasks.add(new TaskNode("task-" + i));
        }
        var root = new InnerGoalNode(Mode.CONSENSUS, tasks);

        ContinuationMock cont = new ContinuationMock();

        Flux<ForestEvent> result = strategy.execute(root, ctx, 0, cont);
        StepVerifier.create(result)
                .expectNextCount(5)
                .verifyComplete();
    }

    @Test
    void childErrorDoesNotFailEntireStrategy() {
        var t1 = new TaskNode("fail");
        var t2 = new TaskNode("ok");
        var root = new InnerGoalNode(Mode.CONSENSUS, List.of(t1, t2));

        Continuation cont = (child, c, d) -> {
            if (child.nodeId().equals(t1.nodeId())) {
                return Flux.error(new RuntimeException("模拟失败"));
            }
            return Flux.just(ForestEvent.detail(child.nodeId(), null, c.accountId().toString(), "ok"));
        };

        Flux<ForestEvent> result = strategy.execute(root, ctx, 0, cont);
        StepVerifier.create(result)
                .expectNextCount(2)
                .verifyComplete();
    }

    // ===== 辅助类 =====

    private static class ContinuationMock implements Continuation {
        final List<ExecutableNode> executedNodes = new ArrayList<>();

        @Override
        public Flux<ForestEvent> execute(ExecutableNode child, ExecutionContext ctx, int depth) {
            executedNodes.add(child);
            return Flux.just(ForestEvent.detail(child.nodeId(), null,
                    ctx.accountId().toString(), child.nodeType()));
        }
    }
}
