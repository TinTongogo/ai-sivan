package com.icusu.sivan.agent.forest;

import com.icusu.sivan.agent.forest.mode.DefaultModeDispatcher;
import com.icusu.sivan.agent.forest.mode.ParallelModeStrategy;
import com.icusu.sivan.agent.forest.mode.SequentialModeStrategy;
import com.icusu.sivan.common.Mode;
import com.icusu.sivan.domain.forest.ForestEvent;
import com.icusu.sivan.domain.forest.context.ExecutionContext;
import com.icusu.sivan.domain.forest.service.CheckpointHandler;
import com.icusu.sivan.domain.forest.service.Continuation;
import com.icusu.sivan.domain.forest.service.ModeDispatcher;
import com.icusu.sivan.domain.forest.service.PauseRequest;
import com.icusu.sivan.domain.forest.tree.ExecutableNode;
import com.icusu.sivan.domain.forest.tree.InnerGoalNode;
import com.icusu.sivan.domain.forest.tree.TaskNode;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link DefaultModeDispatcher} 单元测试。
 */
class DefaultModeDispatcherTest {

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

    private final ModeDispatcher dispatcher = new DefaultModeDispatcher(List.of(
            new SequentialModeStrategy(noopCheckpoint)
    ));

    private final ExecutionContext ctx = ExecutionContext.create(UUID.randomUUID());

    @Test
    void dispatchKnownMode() {
        var child = new TaskNode("step-1");
        var root = new InnerGoalNode(Mode.SEQUENTIAL, List.of(child));

        Continuation cont = (n, c, d) -> Flux.just(
                ForestEvent.lifecycle(n.nodeId(), null, c.accountId().toString(),
                        ForestEvent.EventType.LIFECYCLE)
        );

        Flux<ForestEvent> result = dispatcher.dispatch(root, ctx, 0, cont);
        StepVerifier.create(result)
                .expectNextCount(1)
                .verifyComplete();
    }

    @Test
    void dispatchUnknownModeReturnsEmpty() {
        var child = new TaskNode("step-1");
        var root = new InnerGoalNode(Mode.CONDITIONAL, List.of(child));

        Continuation cont = (n, c, d) -> Flux.empty();

        Flux<ForestEvent> result = dispatcher.dispatch(root, ctx, 0, cont);
        StepVerifier.create(result)
                .verifyComplete();
    }

    @Test
    void dispatchWithNullContinuation() {
        var child = new TaskNode("child");
        var root = new InnerGoalNode(Mode.SEQUENTIAL, List.of(child));

        assertThrows(NullPointerException.class,
                () -> dispatcher.dispatch(root, ctx, 0, null));
    }

    @Test
    void dispatchMultipleStrategies() {
        var multiDispatcher = new DefaultModeDispatcher(List.of(
                new SequentialModeStrategy(noopCheckpoint),
                new ParallelModeStrategy(noopCheckpoint)
        ));

        var child = new TaskNode("p");
        var root = new InnerGoalNode(Mode.PARALLEL, List.of(child));

        Continuation cont = (n, c, d) -> Flux.just(
                ForestEvent.lifecycle(n.nodeId(), null, c.accountId().toString(),
                        ForestEvent.EventType.LIFECYCLE)
        );

        Flux<ForestEvent> result = multiDispatcher.dispatch(root, ctx, 0, cont);
        StepVerifier.create(result)
                .expectNextCount(1)
                .verifyComplete();
    }
}
