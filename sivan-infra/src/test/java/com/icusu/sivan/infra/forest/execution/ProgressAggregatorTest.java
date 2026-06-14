package com.icusu.sivan.infra.forest.execution;

import com.icusu.sivan.common.Mode;
import com.icusu.sivan.common.NodeStatus;
import com.icusu.sivan.domain.forest.context.Progress;
import com.icusu.sivan.domain.forest.tree.InnerGoalNode;
import com.icusu.sivan.domain.forest.tree.TaskNode;
import com.icusu.sivan.domain.forest.tree.TreeNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ProgressAggregator} 单元测试。
 */
class ProgressAggregatorTest {

    private final ProgressAggregator aggregator = new ProgressAggregator();

    @Test
    void nullTreeReturnsZero() {
        Progress p = aggregator.aggregate(null);
        assertEquals(Progress.ZERO, p);
    }

    @Test
    void singlePendingNode() {
        var node = new TaskNode("test");
        Progress p = aggregator.aggregate(node);
        assertEquals(0, p.completed());
        assertEquals(0, p.activated());
        assertEquals(1, p.total());
        assertEquals(0, p.depth());
    }

    @Test
    void singleCompletedNode() {
        var node = new TaskNode("test");
        node.setStatus(NodeStatus.COMPLETED);
        Progress p = aggregator.aggregate(node);
        assertEquals(1, p.completed());
        assertEquals(1, p.activated());
        assertEquals(1, p.total());
    }

    @Test
    void singleRunningNode() {
        var node = new TaskNode("test");
        node.setStatus(NodeStatus.RUNNING);
        Progress p = aggregator.aggregate(node);
        assertEquals(0, p.completed());
        assertEquals(1, p.activated());
        assertEquals(1, p.total());
    }

    @Test
    void mixedStatusTree() {
        // root (SEQUENTIAL inner_goal)
        //   ├── task1 (COMPLETED)
        //   └── task2 (PENDING)
        var task1 = new TaskNode("done");
        task1.setStatus(NodeStatus.COMPLETED);
        var task2 = new TaskNode("pending");
        var root = new InnerGoalNode(Mode.SEQUENTIAL, List.of(task1, task2));

        Progress p = aggregator.aggregate(root);
        assertEquals(1, p.completed());
        assertEquals(1, p.activated());  // root is RUNNING (executable), task1 completed, task2 pending
        assertEquals(3, p.total());
        // depth: root(0) → task1(1), task2(1) = 1
        assertEquals(1, p.depth());
    }

    @Test
    void nestedTreeDepth() {
        // root
        //   └── child
        //         └── grandchild (COMPLETED)
        var grandchild = new TaskNode("gc");
        grandchild.setStatus(NodeStatus.COMPLETED);
        var child = new InnerGoalNode(Mode.SEQUENTIAL, List.of(grandchild));
        var root = new InnerGoalNode(Mode.SEQUENTIAL, List.of(child));

        Progress p = aggregator.aggregate(root);
        assertEquals(1, p.completed());
        assertEquals(3, p.total());
        assertEquals(2, p.depth());
    }

    @Test
    void allCompletedTree() {
        var task1 = new TaskNode("a");
        task1.setStatus(NodeStatus.COMPLETED);
        var task2 = new TaskNode("b");
        task2.setStatus(NodeStatus.COMPLETED);
        var root = new InnerGoalNode(Mode.SEQUENTIAL, List.of(task1, task2));
        root.setStatus(NodeStatus.COMPLETED);

        Progress p = aggregator.aggregate(root);
        assertEquals(3, p.completed());
        assertEquals(3, p.activated());
        assertEquals(3, p.total());
    }

    @Test
    void failedNodeCountsAsActivated() {
        var task = new TaskNode("fail");
        task.setStatus(NodeStatus.FAILED);
        Progress p = aggregator.aggregate(task);
        assertEquals(0, p.completed());
        assertEquals(1, p.activated());
        assertEquals(1, p.total());
    }

    @Test
    void cancelledNodeCountsAsActivated() {
        var task = new TaskNode("cancel");
        task.setStatus(NodeStatus.CANCELLED);
        Progress p = aggregator.aggregate(task);
        assertEquals(0, p.completed());
        assertEquals(1, p.activated());
        assertEquals(1, p.total());
    }
}
