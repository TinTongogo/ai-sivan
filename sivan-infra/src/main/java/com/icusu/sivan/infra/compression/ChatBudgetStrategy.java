package com.icusu.sivan.infra.compression;

import com.icusu.sivan.domain.compression.BudgetAllocationStrategy;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * CHAT 场景预算分配策略。
 * <p>
 * conversation 60%, goal 20%, memory 10%, file 10%
 */
@Component
public class ChatBudgetStrategy implements BudgetAllocationStrategy {

    @Override
    public String scene() { return "CHAT"; }

    @Override
    public Map<String, Integer> allocate(int total) {
        return Map.of(
            "conversation", (int) (total * 0.60),
            "goal",         (int) (total * 0.20),
            "memory",       (int) (total * 0.10),
            "file",         (int) (total * 0.10)
        );
    }
}
