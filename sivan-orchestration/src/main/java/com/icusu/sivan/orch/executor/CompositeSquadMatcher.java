package com.icusu.sivan.orch.executor;

import com.icusu.sivan.orch.topology.CompositeMatchSpec;
import com.icusu.sivan.orch.topology.KeywordFuzzySpec;
import com.icusu.sivan.orch.topology.NameContainSpec;
import com.icusu.sivan.domain.orchestration.Squad;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 复合 Squad 匹配器（默认实现）。
 * 加权组合名称匹配和关键词模糊匹配，按综合得分选出最佳匹配。
 */
@Component
public class CompositeSquadMatcher implements SquadMatcher {

    private final CompositeMatchSpec composite = new CompositeMatchSpec()
            .add(new NameContainSpec(), 0.5)
            .add(new KeywordFuzzySpec(), 0.5);

    @Override
    public Squad match(String taskDescription, List<Squad> candidates) {
        return composite.findBest(taskDescription, candidates);
    }
}
