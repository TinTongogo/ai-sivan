package com.icusu.sivan.infra.compression;

import com.icusu.sivan.domain.compression.BudgetAllocationStrategy;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * SQUAD 场景预算分配策略。
 * <p>
 * conversation 30%, goal 40%, memory 15%, file 15%
 */
@Component
public class SquadBudgetStrategy implements BudgetAllocationStrategy {

    @Override
    public String scene() { return "SQUAD"; }

    @Override
    public Map<String, Integer> allocate(int total) {
        return Map.of(
            "conversation", (int) (total * 0.30),
            "goal",         (int) (total * 0.40),
            "memory",       (int) (total * 0.15),
            "file",         (int) (total * 0.15)
        );
    }
}
