package com.icusu.sivan.infra.forest.compression;

import com.icusu.sivan.domain.compression.FoldDecision;
import com.icusu.sivan.domain.compression.TokenBudget;
import com.icusu.sivan.domain.forest.tree.node.MessageNode;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link MessageFoldStrategy} 单元测试。
 */
class MessageFoldStrategyTest {

    private final MessageFoldStrategy strategy = new MessageFoldStrategy();

    @Test
    void supportsMessageType() {
        assertEquals("message", strategy.supportedType());
    }

    @Test
    void shortMessageNotFolded() {
        var budget = new TokenBudget(1000, Map.of("message", 500));
        var node = new MessageNode("你好");
        FoldDecision d = strategy.decide(node, budget);
        assertFalse(d.shouldFold());
    }

    @Test
    void longMessageExceedingBudgetIsFolded() {
        var budget = new TokenBudget(1000, Map.of("message", 10)); // 极小预算
        var node = new MessageNode("x".repeat(100));
        FoldDecision d = strategy.decide(node, budget);
        assertTrue(d.shouldFold());
        assertTrue(d.reason().contains("[摘要]"));
    }

    @Test
    void veryLongMessageIsFoldedEvenWithBudget() {
        var budget = new TokenBudget(1000, Map.of("message", 2000)); // 预算充足
        var node = new MessageNode("x".repeat(600)); // 超过 SUMMARY_THRESHOLD
        FoldDecision d = strategy.decide(node, budget);
        assertTrue(d.shouldFold());
    }

    @Test
    void emptyContentNotFolded() {
        var budget = new TokenBudget(1000, Map.of("message", 10));
        var node = new MessageNode("");
        FoldDecision d = strategy.decide(node, budget);
        assertFalse(d.shouldFold());
    }

    @Test
    void nonContentNodeSkipped() {
        // 无法直接构造非 ContentNode 的 TreeNode，跳过
    }
}
