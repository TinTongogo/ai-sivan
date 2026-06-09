package com.icusu.sivan.domain.forest.service;

/**
 * 预算执行器 — 检查执行是否超出 token/时间/深度预算。
 */
public interface BudgetEnforcer {

    BudgetResult checkToken(long estimatedTokens, long maxTokens);

    BudgetResult checkDepth(int depth, int maxDepth);

    BudgetResult checkTime(long elapsedMs, long timeoutMs);

    record BudgetResult(boolean allowed, String reason) {
        public static BudgetResult ok() { return new BudgetResult(true, null); }
        public static BudgetResult exceeded(String reason) { return new BudgetResult(false, reason); }
    }
}
