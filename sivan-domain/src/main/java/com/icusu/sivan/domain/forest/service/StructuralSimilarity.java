package com.icusu.sivan.domain.forest.service;

import com.icusu.sivan.domain.forest.tree.TreeNode;

import java.util.List;

/**
 * 树结构相似度 — 比较两棵执行树的拓扑相似程度。
 * <p>
 * 考虑三个维度加权：
 * <ul>
 *   <li>深度相似度（0.4）</li>
 *   <li>Mode 分布相似度（0.3）</li>
 *   <li>节点规模相似度（0.3）</li>
 * </ul>
 */
public class StructuralSimilarity {

    private StructuralSimilarity() {}

    /**
     * 计算两棵树的相似度 [0, 1]。
     */
    public static double compute(TreeNode template, TreeNode actual) {
        if (template == null && actual == null) return 1.0;
        if (template == null || actual == null) return 0.0;

        double depthScore = scoreDepth(template, actual);
        double modeScore = scoreMode(template, actual);
        double sizeScore = scoreSize(template, actual);

        return 0.4 * depthScore + 0.3 * modeScore + 0.3 * sizeScore;
    }

    private static double scoreDepth(TreeNode a, TreeNode b) {
        int da = maxDepth(a);
        int db = maxDepth(b);
        if (da == 0 && db == 0) return 1.0;
        return 1.0 - (double) Math.abs(da - db) / Math.max(da, db);
    }

    private static double scoreMode(TreeNode a, TreeNode b) {
        List<com.icusu.sivan.common.Mode> modesA = collectModes(a);
        List<com.icusu.sivan.common.Mode> modesB = collectModes(b);
        int min = Math.min(modesA.size(), modesB.size());
        long same = 0;
        for (int i = 0; i < min; i++) {
            if (modesA.get(i) == modesB.get(i)) same++;
        }
        return (double) same / Math.max(modesA.size(), modesB.size());
    }

    private static double scoreSize(TreeNode a, TreeNode b) {
        int sa = countNodes(a);
        int sb = countNodes(b);
        if (sa == 0 && sb == 0) return 1.0;
        return 1.0 - (double) Math.abs(sa - sb) / Math.max(sa, sb);
    }

    private static int maxDepth(TreeNode node) {
        if (node == null || node.isLeaf()) return 0;
        return node.children().stream()
                .mapToInt(StructuralSimilarity::maxDepth)
                .max().orElse(0) + 1;
    }

    private static int countNodes(TreeNode node) {
        if (node == null) return 0;
        return 1 + node.children().stream()
                .mapToInt(StructuralSimilarity::countNodes)
                .sum();
    }

    private static java.util.List<com.icusu.sivan.common.Mode> collectModes(TreeNode node) {
        java.util.ArrayList<com.icusu.sivan.common.Mode> modes = new java.util.ArrayList<>();
        if (node instanceof com.icusu.sivan.domain.forest.tree.ExecutableNode en) {
            modes.add(en.mode());
        }
        for (TreeNode child : node.children()) {
            modes.addAll(collectModes(child));
        }
        return modes;
    }
}
