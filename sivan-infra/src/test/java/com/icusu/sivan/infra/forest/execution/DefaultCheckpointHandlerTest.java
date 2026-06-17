package com.icusu.sivan.infra.forest.execution;

import com.icusu.sivan.domain.forest.ForestEvent;
import com.icusu.sivan.domain.forest.context.ExecutionContext;
import com.icusu.sivan.domain.shared.port.EventSink;
import com.icusu.sivan.domain.forest.tree.node.TaskNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link DefaultCheckpointHandler} 单元测试。
 */
class DefaultCheckpointHandlerTest {

    private final List<ForestEvent> emitted = new ArrayList<>();
    private final EventSink eventSink = emitted::add;
    private final ExecutionContext ctx = ExecutionContext.create(UUID.randomUUID());
    private DefaultCheckpointHandler handler;

    @BeforeEach
    void setUp() {
        emitted.clear();
        handler = new DefaultCheckpointHandler(eventSink, false, 0);
    }

    @Test
    void checkEmitsPauseEvent() {
        var node = new TaskNode("test-content");
        handler.check(node, ctx).subscribe();

        assertEquals(1, emitted.size());
        assertEquals(ForestEvent.EventType.PAUSE, emitted.get(0).type());
    }

    @Test
    void approveReleasesMonoWithApproved() {
        var node = new TaskNode("test-approve");
        String nodeId = node.nodeId();

        StepVerifier.create(handler.check(node, ctx))
                .then(() -> handler.approve(nodeId, ctx.accountId().toString()))
                .assertNext(req -> {
                    assertTrue(req.isApproved());
                    assertFalse(req.isRejected());
                })
                .verifyComplete();
    }

    @Test
    void rejectReleasesMonoWithRejected() {
        var node = new TaskNode("test-reject");
        String nodeId = node.nodeId();

        StepVerifier.create(handler.check(node, ctx))
                .then(() -> handler.reject(nodeId, ctx.accountId().toString(), "不需要"))
                .assertNext(req -> {
                    assertTrue(req.isRejected());
                    assertFalse(req.isApproved());
                })
                .verifyComplete();
    }

    @Test
    void approveUnknownNodeDoesNotThrow() {
        assertDoesNotThrow(() -> handler.approve("non-existent", ctx.accountId().toString()));
    }

    @Test
    void rejectUnknownNodeDoesNotThrow() {
        assertDoesNotThrow(() -> handler.reject("non-existent", ctx.accountId().toString(), "reason"));
    }

    @Test
    void pendingCountTracksPendingApprovals() {
        var node1 = new TaskNode("task-1");
        var node2 = new TaskNode("task-2");

        handler.check(node1, ctx).subscribe();
        assertEquals(1, handler.pendingCount());

        handler.check(node2, ctx).subscribe();
        assertEquals(2, handler.pendingCount());

        handler.approve(node1.nodeId(), ctx.accountId().toString());
        assertEquals(1, handler.pendingCount());

        handler.reject(node2.nodeId(), ctx.accountId().toString(), "no");
        assertEquals(0, handler.pendingCount());
    }

    @Test
    void multipleApprovalsInSequence() {
        var node1 = new TaskNode("seq-1");
        var node2 = new TaskNode("seq-2");

        StepVerifier.create(handler.check(node1, ctx))
                .then(() -> handler.approve(node1.nodeId(), ctx.accountId().toString()))
                .assertNext(req -> assertTrue(req.isApproved()))
                .verifyComplete();

        StepVerifier.create(handler.check(node2, ctx))
                .then(() -> handler.approve(node2.nodeId(), ctx.accountId().toString()))
                .assertNext(req -> assertTrue(req.isApproved()))
                .verifyComplete();
    }
}
