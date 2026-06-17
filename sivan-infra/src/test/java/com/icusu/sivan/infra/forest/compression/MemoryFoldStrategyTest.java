package com.icusu.sivan.infra.forest.compression;

import com.icusu.sivan.domain.compression.FoldDecision;
import com.icusu.sivan.domain.compression.TokenBudget;
import com.icusu.sivan.domain.forest.tree.node.MemoryNode;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link MemoryFoldStrategy} 单元测试。
 */
class MemoryFoldStrategyTest {

    private final MemoryFoldStrategy strategy = new MemoryFoldStrategy();

    @Test
    void supportsMemoryType() {
        assertEquals("memory", strategy.supportedType());
    }

    @Test
    void lowImportanceMemoryIsFolded() {
        var budget = new TokenBudget(1000, Map.of("memory", 500));
        var node = new MemoryNode("一些不重要的历史信息", 0.1);
        FoldDecision d = strategy.decide(node, budget);
        assertTrue(d.shouldFold());
    }

    @Test
    void highImportanceMemoryWithinBudgetNotFolded() {
        var budget = new TokenBudget(1000, Map.of("memory", 500));
        var node = new MemoryNode("重要的用户偏好信息", 0.8);
        FoldDecision d = strategy.decide(node, budget);
        assertFalse(d.shouldFold());
    }

    @Test
    void highImportanceMemoryExceedingBudgetIsFolded() {
        var budget = new TokenBudget(1000, Map.of("memory", 10)); // 极小预算
        var node = new MemoryNode("x".repeat(100), 0.8);
        FoldDecision d = strategy.decide(node, budget);
        assertTrue(d.shouldFold());
    }

    @Test
    void emptyContentNotFolded() {
        var budget = new TokenBudget(1000, Map.of("memory", 10));
        var node = new MemoryNode("", 0.5);
        FoldDecision d = strategy.decide(node, budget);
        assertFalse(d.shouldFold());
    }
}
