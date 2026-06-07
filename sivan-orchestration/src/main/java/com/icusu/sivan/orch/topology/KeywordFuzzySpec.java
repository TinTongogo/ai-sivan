package com.icusu.sivan.orch.topology;

import com.icusu.sivan.domain.orchestration.Squad;

/**
 * 关键词模糊匹配规范。按描述中关键词密度评分。
 */
public class KeywordFuzzySpec implements SquadMatchSpecification {

    private static final double MIN_MATCH_RATIO = 0.4;

    @Override
    public boolean isSatisfiedBy(Squad squad, String taskDescription) {
        return matchScore(squad, taskDescription) >= MIN_MATCH_RATIO;
    }

    @Override
    public double matchScore(Squad squad, String taskDescription) {
        if (squad.getDescription() == null || taskDescription == null) return 0;
        String task = taskDescription.toLowerCase();
        String[] keywords = squad.getDescription().toLowerCase().split("[\\s，,、]+");
        long matchCount = 0;
        for (String kw : keywords) {
            if (kw.length() > 1 && task.contains(kw)) matchCount++;
        }
        if (keywords.length == 0) return 0;
        return (double) matchCount / keywords.length;
    }

    @Override
    public String name() {
        return "关键词模糊匹配";
    }
}
