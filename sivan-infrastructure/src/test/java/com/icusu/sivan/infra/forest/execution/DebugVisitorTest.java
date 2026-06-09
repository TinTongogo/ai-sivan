package com.icusu.sivan.infra.forest.execution;

import com.icusu.sivan.common.Mode;
import com.icusu.sivan.common.NodeStatus;
import com.icusu.sivan.domain.forest.tree.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link DebugVisitor} 单元测试。
 */
class DebugVisitorTest {

    @Test
    void dumpSingleTaskNode() {
        var node = new TaskNode("hello");
        String dump = DebugVisitor.dump(node);
        assertTrue(dump.contains("task"));
        assertTrue(dump.contains("PENDING"));
        assertTrue(dump.contains("hello"));
    }

    @Test
    void dumpCompletedTaskNode() {
        var node = new TaskNode("done");
        node.setStatus(NodeStatus.COMPLETED);
        String dump = DebugVisitor.dump(node);
        assertTrue(dump.contains("COMPLETED"));
    }

    @Test
    void dumpTreeStructure() {
        var task1 = new TaskNode("step 1");
        task1.setStatus(NodeStatus.COMPLETED);
        var task2 = new TaskNode("step 2");
        var root = new InnerGoalNode(Mode.SEQUENTIAL, List.of(task1, task2));

        String dump = DebugVisitor.dump(root);
        assertTrue(dump.contains("inner_goal"));
        assertTrue(dump.contains("task"));
        assertTrue(dump.contains("COMPLETED"));
        assertTrue(dump.contains("PENDING"));
    }

    @Test
    void dumpEmptyContent() {
        var node = new TaskNode("");
        String dump = DebugVisitor.dump(node);
        // 空 content 不应出现 content=""
        assertFalse(dump.contains("content=\"\""));
    }

    @Test
    void dumpNullContentInSynthesisNode() {
        var node = new SynthesisNode();
        String dump = DebugVisitor.dump(node);
        assertTrue(dump.contains("synthesis"));
    }

    @Test
    void dumpTruncatesLongContent() {
        var node = new TaskNode("a".repeat(200));
        String dump = DebugVisitor.dump(node);
        assertTrue(dump.contains("..."));
        assertTrue(dump.length() < 300); // 截断后不会太长
    }

    @Test
    void dumpDeeplyNestedTree() {
        // inner_goal
        //   └── inner_goal
        //         └── inner_goal
        //               └── task
        var l3 = new InnerGoalNode(Mode.SEQUENTIAL, List.of(new TaskNode("leaf")));
        var l2 = new InnerGoalNode(Mode.SEQUENTIAL, List.of(l3));
        var l1 = new InnerGoalNode(Mode.SEQUENTIAL, List.of(l2));

        String dump = DebugVisitor.dump(l1);
        String[] lines = dump.split("\n");
        assertEquals(4, lines.length);
    }

    @Test
    void dumpMultipleChildren() {
        var t1 = new TaskNode("a");
        var t2 = new TaskNode("b");
        var t3 = new TaskNode("c");
        var root = new InnerGoalNode(Mode.PARALLEL, List.of(t1, t2, t3));

        String dump = DebugVisitor.dump(root);
        assertEquals(4, dump.split("\n").length);
        assertTrue(dump.contains("a"));
        assertTrue(dump.contains("b"));
        assertTrue(dump.contains("c"));
    }

    @Test
    void dumpUsesNodeTypeNotClassName() {
        var node = new TaskNode("test");
        String dump = DebugVisitor.dump(node);
        assertTrue(dump.contains("task"));
        assertFalse(dump.contains("TaskNode"));
    }

    @Test
    void resultMethodIsIdempotent() {
        var node = new TaskNode("hello");
        DebugVisitor v = new DebugVisitor();
        node.accept(v);
        String r1 = v.result();
        String r2 = v.result();
        assertEquals(r1, r2);
    }
}
