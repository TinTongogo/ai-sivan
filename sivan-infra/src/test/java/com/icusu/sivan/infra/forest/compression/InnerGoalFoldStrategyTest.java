package com.icusu.sivan.infra.forest.compression;

import com.icusu.sivan.common.Mode;
import com.icusu.sivan.common.NodeStatus;
import com.icusu.sivan.domain.compression.FoldDecision;
import com.icusu.sivan.domain.compression.TokenBudget;
import com.icusu.sivan.domain.forest.tree.node.InnerGoalNode;
import com.icusu.sivan.domain.forest.tree.node.TaskNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link InnerGoalFoldStrategy} 单元测试。
 */
class InnerGoalFoldStrategyTest {

    private final InnerGoalFoldStrategy strategy = new InnerGoalFoldStrategy();
    private final TokenBudget budget = new TokenBudget(1000, Map.of("inner_goal", 5));

    @Test
    void supportsInnerGoalType() {
        assertEquals("inner_goal", strategy.supportedType());
    }

    @Test
    void completedGoalIsFolded() {
        var child1 = new TaskNode("task-1");
        child1.setStatus(NodeStatus.COMPLETED);
        var child2 = new TaskNode("task-2");
        child2.setStatus(NodeStatus.COMPLETED);
        var goal = new InnerGoalNode(Mode.SEQUENTIAL, List.of(child1, child2));
        goal.setStatus(NodeStatus.COMPLETED);

        FoldDecision d = strategy.decide(goal, budget);
        assertTrue(d.shouldFold());
        assertTrue(d.reason().contains("✅"));
        assertTrue(d.reason().contains("2/2"));
    }

    @Test
    void runningGoalNotFolded() {
        var goal = new InnerGoalNode(Mode.SEQUENTIAL, List.of());
        goal.setStatus(NodeStatus.RUNNING);

        FoldDecision d = strategy.decide(goal, budget);
        assertFalse(d.shouldFold());
    }

    @Test
    void failedGoalIsFolded() {
        var goal = new InnerGoalNode(Mode.SEQUENTIAL, List.of());
        goal.setStatus(NodeStatus.FAILED);

        FoldDecision d = strategy.decide(goal, budget);
        assertTrue(d.shouldFold());
        assertTrue(d.reason().contains("❌"));
    }

    @Test
    void pendingGoalWithManyChildrenExceedsBudget() {
        var budget = new TokenBudget(1000, Map.of("inner_goal", 1)); // 只能有 1 个子节点
        var tasks = List.of(new TaskNode("a"), new TaskNode("b"), new TaskNode("c"));
        var goal = new InnerGoalNode(Mode.SEQUENTIAL, tasks);
        // PENDING 是默认状态

        FoldDecision d = strategy.decide(goal, budget);
        assertTrue(d.shouldFold());
    }

    @Test
    void pendingGoalWithinBudgetNotFolded() {
        var tasks = List.of(new TaskNode("a"));
        var goal = new InnerGoalNode(Mode.SEQUENTIAL, tasks);
        // PENDING 默认，但有充足预算

        FoldDecision d = strategy.decide(goal, budget);
        assertFalse(d.shouldFold());
    }
}
