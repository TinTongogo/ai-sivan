package com.icusu.sivan.domain.compression;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Token 预算管理器 — 按场景委托 BudgetAllocationStrategy 分配预算。
 * <p>
 * 新增场景只需新增一个 BudgetAllocationStrategy 实现 + 注册。
 */
public class TokenBudgetManager {

    private final Map<String, BudgetAllocationStrategy> strategies;

    public TokenBudgetManager(List<BudgetAllocationStrategy> strategyList) {
        ConcurrentHashMap<String, BudgetAllocationStrategy> map = new ConcurrentHashMap<>();
        if (strategyList != null) {
            for (BudgetAllocationStrategy s : strategyList) {
                map.put(s.scene(), s);
            }
        }
        this.strategies = map;
    }

    /**
     * 按场景分配预算。
     * @param scene     场景名（CHAT / SQUAD / 等）
     * @param totalBudget 总 token 预算
     * @return 类型 → token 预算映射
     */
    public Map<String, Integer> allocate(String scene, int totalBudget) {
        BudgetAllocationStrategy strategy = strategies.get(scene);
        if (strategy == null) {
            // 默认：等分
            return Map.of("conversation", totalBudget);
        }
        return strategy.allocate(totalBudget);
    }

    /** 注册自定义策略。 */
    public void register(BudgetAllocationStrategy strategy) {
        strategies.put(strategy.scene(), strategy);
    }
}
