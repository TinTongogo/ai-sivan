package com.icusu.sivan.orch.topology;

import com.icusu.sivan.domain.orchestration.Squad;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 组合匹配规范。加权组合多个规范评分，选出最佳匹配 Squad。
 */
public class CompositeMatchSpec {

    private final List<WeightedSpec> specs = new ArrayList<>();
    private static final double MIN_THRESHOLD = 0.4;

    public CompositeMatchSpec add(SquadMatchSpecification spec, double weight) {
        specs.add(new WeightedSpec(spec, weight));
        return this;
    }

    /**
     * 从候选 Squad 列表中选出最佳匹配。
     * @return 匹配的 Squad，无匹配返回 null
     */
    public Squad findBest(String taskDescription, List<Squad> candidates) {
        if (candidates == null || candidates.isEmpty()) return null;

        return candidates.stream()
                .map(s -> new ScoredSquad(s, compositeScore(s, taskDescription)))
                .filter(s -> s.score >= MIN_THRESHOLD)
                .max(Comparator.comparingDouble(s -> s.score))
                .map(s -> s.squad)
                .orElse(null);
    }

    private double compositeScore(Squad squad, String taskDescription) {
        double totalWeight = 0, weightedScore = 0;
        for (WeightedSpec ws : specs) {
            double score = ws.spec.matchScore(squad, taskDescription);
            weightedScore += score * ws.weight;
            totalWeight += ws.weight;
        }
        return totalWeight > 0 ? weightedScore / totalWeight : 0;
    }

    private record WeightedSpec(SquadMatchSpecification spec, double weight) {}
    private record ScoredSquad(Squad squad, double score) {}
}
