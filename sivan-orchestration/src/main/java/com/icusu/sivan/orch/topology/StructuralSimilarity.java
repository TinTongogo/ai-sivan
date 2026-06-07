package com.icusu.sivan.orch.topology;

import com.icusu.sivan.domain.orchestration.PhaseNode;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 拓扑结构相似度计算工具。
 * 基于 phase 数量和 agent 名称集合的 Jaccard 相似度，
 * 用于对比 LLM 生成拓扑与模板拓扑的偏差程度。
 */
public class StructuralSimilarity {

    private StructuralSimilarity() {}

    /**
     * 计算两个阶段列表的 structural similarity。
     * 综合 phase 数量差异 + agent 名称集合的 Jaccard 相似度。
     *
     * @return 0.0 ~ 1.0，越高越相似
     */
    public static double compute(List<PhaseNode> template, List<PhaseNode> generated) {
        if (template == null || generated == null) return 0.0;
        if (template.isEmpty() && generated.isEmpty()) return 1.0;
        if (template.isEmpty() || generated.isEmpty()) return 0.0;

        // phase 数量相似度：min/max
        double phaseCountSim = (double) Math.min(template.size(), generated.size())
                / Math.max(template.size(), generated.size());

        // agent 名称集合 Jaccard 相似度
        Set<String> templateAgents = extractAgentNames(template);
        Set<String> generatedAgents = extractAgentNames(generated);
        double jaccard = jaccardSimilarity(templateAgents, generatedAgents);

        // 综合：数量相似度 0.3 + Jaccard 0.7
        return 0.3 * phaseCountSim + 0.7 * jaccard;
    }

    /**
     * 提取阶段列表中的所有 agent 名称。
     */
    static Set<String> extractAgentNames(List<PhaseNode> phases) {
        Set<String> names = new HashSet<>();
        for (PhaseNode phase : phases) {
            if (phase.getAgents() != null) {
                names.addAll(phase.getAgents());
            }
        }
        return names;
    }

    /**
     * 计算两个集合的 Jaccard 相似度。
     */
    static double jaccardSimilarity(Set<String> a, Set<String> b) {
        if (a.isEmpty() && b.isEmpty()) return 1.0;
        Set<String> intersection = new HashSet<>(a);
        intersection.retainAll(b);
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return (double) intersection.size() / union.size();
    }
}
