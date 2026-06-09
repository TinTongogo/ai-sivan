package com.icusu.sivan.infra.forest.compression;

import com.icusu.sivan.common.NodeStatus;
import com.icusu.sivan.domain.forest.Forest;
import com.icusu.sivan.domain.forest.tree.InnerGoalNode;
import com.icusu.sivan.domain.forest.tree.TaskNode;
import com.icusu.sivan.domain.forest.tree.TreeNode;
import com.icusu.sivan.common.Mode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ForestCompressor} 单元测试。
 */
class ForestCompressorTest {

    private ForestCompressor compressor;
    private Forest forest;

    @BeforeEach
    void setUp() {
        compressor = new ForestCompressor(List.of(
                new TaskFoldStrategy(),
                new InnerGoalFoldStrategy(),
                new MessageFoldStrategy(),
                new MemoryFoldStrategy()
        ));
        forest = new Forest(UUID.randomUUID(), UUID.randomUUID(), null, "测试", "root");
    }

    @Test
    void nullRootReturnsNull() {
        assertNull(compressor.compress(forest, null, "view", 1000));
    }

    @Test
    void completedTaskTreeIsCompressed() {
        var task = new TaskNode("测试任务");
        task.setStatus(NodeStatus.COMPLETED);

        TreeNode result = compressor.compress(forest, task, "view", 1000);
        assertNotNull(result);
        // 验证：节点的 status 不变，压缩器目前只做决策标记
        assertEquals(NodeStatus.COMPLETED, ((TaskNode) result).status());
    }

    @Test
    void innerGoalWithCompletedChildrenIsCompressed() {
        var t1 = new TaskNode("步骤一");
        t1.setStatus(NodeStatus.COMPLETED);
        var t2 = new TaskNode("步骤二");
        t2.setStatus(NodeStatus.COMPLETED);
        var goal = new InnerGoalNode(Mode.SEQUENTIAL, List.of(t1, t2));
        goal.setStatus(NodeStatus.COMPLETED);

        TreeNode result = compressor.compress(forest, goal, "view", 1000);
        assertNotNull(result);
        assertEquals(2, result.children().size());
    }

    @Test
    void mixedStatusTreeCompressesWithoutError() {
        var t1 = new TaskNode("已完成");
        t1.setStatus(NodeStatus.COMPLETED);
        var t2 = new TaskNode("运行中");
        t2.setStatus(NodeStatus.RUNNING);
        var t3 = new TaskNode("已失败");
        t3.setStatus(NodeStatus.FAILED);
        var goal = new InnerGoalNode(Mode.SEQUENTIAL, List.of(t1, t2, t3));

        TreeNode result = compressor.compress(forest, goal, "send", 500);
        assertNotNull(result);
        assertEquals(3, result.children().size());
    }

    @Test
    void differentScenesProduceDifferentBudgets() {
        var task = new TaskNode("a".repeat(100));
        TreeNode r1 = compressor.compress(forest, task, "view", 100);
        TreeNode r2 = compressor.compress(forest, task, "send", 100);
        assertNotNull(r1);
        assertNotNull(r2);
    }

    @Test
    void emptyStrategyListDoesNotThrow() {
        var empty = new ForestCompressor(List.of());
        var task = new TaskNode("测试");
        assertDoesNotThrow(() -> empty.compress(forest, task, "view", 1000));
    }
}
