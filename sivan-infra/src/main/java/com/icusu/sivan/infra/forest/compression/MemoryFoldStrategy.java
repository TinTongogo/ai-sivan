package com.icusu.sivan.infra.forest.compression;

import com.icusu.sivan.domain.compression.FoldDecision;
import com.icusu.sivan.domain.compression.FoldStrategy;
import com.icusu.sivan.domain.compression.TokenBudget;
import com.icusu.sivan.domain.forest.tree.ContentNode;
import com.icusu.sivan.domain.forest.tree.TreeNode;
import com.icusu.sivan.domain.forest.tree.node.MemoryNode;
import org.springframework.stereotype.Component;

/**
 * MemoryNode 折叠策略。
 * <p>
 * 低重要性（importance &lt; 0.3）→ 折叠/删除；
 * 高重要性 → 保留，超预算时摘要。
 */
@Component
public class MemoryFoldStrategy implements FoldStrategy {

    /** 低重要性阈值，与 CompressibleNode.isFoldable() 一致 */
    private static final double LOW_IMPORTANCE = 0.3;

    @Override
    public String supportedType() {
        return "memory";
    }

    @Override
    public FoldDecision decide(TreeNode node, TokenBudget budget) {
        if (!(node instanceof ContentNode cn)) {
            return FoldDecision.skip("不是 ContentNode");
        }

        String content = cn.content();
        if (content == null || content.isBlank()) {
            return FoldDecision.skip("内容为空");
        }

        double importance = 0.0;
        if (node instanceof MemoryNode mem) {
            importance = mem.importance();
        }

        // 低重要性 → 折叠
        if (importance < LOW_IMPORTANCE) {
            String hint = truncate(content, 30);
            return FoldDecision.fold("[记忆] " + hint);
        }

        // 高重要性但超出预算 → 摘要
        long tokens = (long) (content.length() * 1.5);
        int memoryBudget = budget.forType("memory");
        if (tokens > memoryBudget && memoryBudget > 0) {
            return FoldDecision.fold("[记忆] " + truncate(content, 50) + "...");
        }

        return FoldDecision.skip("重要记忆，预算充足");
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
