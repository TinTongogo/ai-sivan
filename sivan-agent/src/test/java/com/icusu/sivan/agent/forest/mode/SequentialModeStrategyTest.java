package com.icusu.sivan.agent.forest.mode;

import com.icusu.sivan.agent.forest.MockContinuation;
import com.icusu.sivan.common.Mode;
import com.icusu.sivan.common.NodeStatus;
import com.icusu.sivan.domain.forest.ForestEvent;
import com.icusu.sivan.domain.forest.context.ExecutionContext;
import com.icusu.sivan.domain.forest.port.CheckpointHandler;
import com.icusu.sivan.domain.forest.port.Continuation;
import com.icusu.sivan.domain.forest.tree.ExecutableNode;
import com.icusu.sivan.domain.forest.tree.InnerGoalNode;
import com.icusu.sivan.domain.forest.tree.TaskNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SequentialModeStrategyTest {

    private final CheckpointHandler noopCheckpoint = new CheckpointHandler() {
        @Override public reactor.core.publisher.Mono<com.icusu.sivan.domain.forest.vo.PauseRequest> check(ExecutableNode node, ExecutionContext ctx) { return reactor.core.publisher.Mono.empty(); }
        @Override public void approve(String nodeId, String accountId) {}
        @Override public void reject(String nodeId, String accountId, String reason) {}
    };
    private final SequentialModeStrategy strategy = new SequentialModeStrategy(noopCheckpoint);
    private final MockContinuation mock = new MockContinuation();
    private final ExecutionContext ctx = ExecutionContext.create(UUID.randomUUID());

    @Test
    void shouldExecuteChildrenInOrder() {
        var node = new InnerGoalNode(Mode.SEQUENTIAL, java.util.List.of(
                new TaskNode("id-A", "A", NodeStatus.PENDING),
                new TaskNode("id-B", "B", NodeStatus.PENDING),
                new TaskNode("id-C", "C", NodeStatus.PENDING)
        ));

        strategy.execute(node, ctx, 0, mock).blockLast();

        assertThat(mock.getInvoked())
                .extracting(n -> ((com.icusu.sivan.domain.forest.tree.ContentNode)n).content())
                .containsExactly("A", "B", "C");
    }

    @Test
    void shouldSkipCompletedChildren() {
        var done = new TaskNode("id-A", "A", NodeStatus.PENDING);
        done.setStatus(NodeStatus.COMPLETED);
        var node = new InnerGoalNode(Mode.SEQUENTIAL, java.util.List.of(
                done,
                new TaskNode("id-B", "B", NodeStatus.PENDING)
        ));

        strategy.execute(node, ctx, 0, mock).blockLast();

        assertThat(mock.getInvoked())
                .extracting(n -> ((com.icusu.sivan.domain.forest.tree.ContentNode)n).content())
                .containsExactly("B");
    }

    @Test
    void emptyChildrenReturnsEmpty() {
        var node = new InnerGoalNode(Mode.SEQUENTIAL, java.util.List.of());

        var result = strategy.execute(node, ctx, 0, mock).blockLast();

        assertThat(result).isNull();
    }
}
