package com.icusu.sivan.orch.topology;

import com.icusu.sivan.domain.orchestration.Squad;

/**
 * 名称包含匹配规范。若任务描述包含 Squad 名称，得分 1.0。
 */
public class NameContainSpec implements SquadMatchSpecification {

    @Override
    public boolean isSatisfiedBy(Squad squad, String taskDescription) {
        return matchScore(squad, taskDescription) >= 1.0;
    }

    @Override
    public double matchScore(Squad squad, String taskDescription) {
        if (squad.getName() == null || taskDescription == null) return 0;
        if (taskDescription.toLowerCase().contains(squad.getName().toLowerCase())) return 1.0;
        // 分词匹配
        String[] keywords = squad.getName().toLowerCase().split("[\\s\\-—–/]+");
        for (String kw : keywords) {
            if (kw.length() >= 2 && taskDescription.toLowerCase().contains(kw)) return 1.0;
        }
        return 0;
    }

    @Override
    public String name() {
        return "名称包含匹配";
    }
}
