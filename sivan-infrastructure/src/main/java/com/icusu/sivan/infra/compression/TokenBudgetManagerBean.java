package com.icusu.sivan.infra.compression;

import com.icusu.sivan.domain.compression.BudgetAllocationStrategy;
import com.icusu.sivan.domain.compression.TokenBudgetManager;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * TokenBudgetManager Spring Bean 包装。
 */
@Component
public class TokenBudgetManagerBean extends TokenBudgetManager {

    public TokenBudgetManagerBean(List<BudgetAllocationStrategy> strategies) {
        super(strategies);
    }
}
