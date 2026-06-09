package com.icusu.sivan.infra.forest.compression;

import com.icusu.sivan.common.NodeStatus;
import com.icusu.sivan.domain.compression.FoldDecision;
import com.icusu.sivan.domain.compression.TokenBudget;
import com.icusu.sivan.domain.forest.tree.TaskNode;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link TaskFoldStrategy} 单元测试。
 */
class TaskFoldStrategyTest {

    private final TaskFoldStrategy strategy = new TaskFoldStrategy();
    private final TokenBudget budget = new TokenBudget(1000, Map.of("task", 200));

    @Test
    void supportsTaskType() {
        assertEquals("task", strategy.supportedType());
    }

    @Test
    void completedTaskIsFolded() {
        var node = new TaskNode("密码重置功能开发");
        node.setStatus(NodeStatus.COMPLETED);
        FoldDecision d = strategy.decide(node, budget);
        assertTrue(d.shouldFold());
        assertTrue(d.reason().contains("✅"));
    }

    @Test
    void failedTaskIsFolded() {
        var node = new TaskNode("集成测试");
        node.setStatus(NodeStatus.FAILED);
        FoldDecision d = strategy.decide(node, budget);
        assertTrue(d.shouldFold());
        assertTrue(d.reason().contains("❌"));
    }

    @Test
    void pendingTaskWithinBudgetNotFolded() {
        var node = new TaskNode("简单任务");
        // PENDING 默认，内容短 → 预算充足
        FoldDecision d = strategy.decide(node, budget);
        assertFalse(d.shouldFold());
    }

    @Test
    void pendingTaskExceedingBudgetIsFolded() {
        var node = new TaskNode("x".repeat(500)); // 超长内容
        FoldDecision d = strategy.decide(node, budget);
        assertTrue(d.shouldFold());
    }

    @Test
    void runningTaskNotFolded() {
        var node = new TaskNode("进行中的任务");
        node.setStatus(NodeStatus.RUNNING);
        FoldDecision d = strategy.decide(node, budget);
        assertFalse(d.shouldFold());
    }

    @Test
    void cancelledTaskIsFolded() {
        var node = new TaskNode("已取消的任务");
        node.setStatus(NodeStatus.CANCELLED);
        FoldDecision d = strategy.decide(node, budget);
        assertTrue(d.shouldFold());
    }
}
