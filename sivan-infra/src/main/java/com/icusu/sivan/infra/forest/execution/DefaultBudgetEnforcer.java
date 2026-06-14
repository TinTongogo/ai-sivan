package com.icusu.sivan.infra.forest.execution;

import com.icusu.sivan.domain.forest.port.BudgetEnforcer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 默认预算执行器 — 深度/Token/时间约束检查。
 * <p>
 * 三个配置项均可通过 {@code forest.budget.*} 覆盖。
 */
@Component
public class DefaultBudgetEnforcer implements BudgetEnforcer {

    private static final Logger log = LoggerFactory.getLogger(DefaultBudgetEnforcer.class);

    private final int maxDepth;
    private final long maxTokens;
    private final long maxTimeMs;

    public DefaultBudgetEnforcer(
            @Value("${forest.budget.max-depth:20}") int maxDepth,
            @Value("${forest.budget.max-tokens:64000}") long maxTokens,
            @Value("${forest.budget.max-time-ms:3600000}") long maxTimeMs) {
        this.maxDepth = maxDepth;
        this.maxTokens = maxTokens;
        this.maxTimeMs = maxTimeMs;
    }

    @Override
    public BudgetResult checkToken(long estimatedTokens, long maxTokens) {
        long effectiveMax = Math.min(maxTokens, this.maxTokens);
        if (estimatedTokens > effectiveMax) {
            log.warn("[Budget] Token 超限: {} > {}", estimatedTokens, effectiveMax);
            return BudgetResult.exceeded("估计 token " + estimatedTokens + " 超过限制 " + effectiveMax);
        }
        return BudgetResult.ok();
    }

    @Override
    public BudgetResult checkDepth(int depth, int maxDepth) {
        long effectiveMax = Math.min(maxDepth, this.maxDepth);
        if (depth > effectiveMax) {
            log.warn("[Budget] 深度超限: {} > {}", depth, effectiveMax);
            return BudgetResult.exceeded("深度 " + depth + " 超过限制 " + effectiveMax);
        }
        return BudgetResult.ok();
    }

    @Override
    public BudgetResult checkTime(long elapsedMs, long timeoutMs) {
        long effectiveMax = Math.min(timeoutMs, this.maxTimeMs);
        if (elapsedMs > effectiveMax) {
            log.warn("[Budget] 时间超限: {}ms > {}ms", elapsedMs, effectiveMax);
            return BudgetResult.exceeded("耗时 " + elapsedMs + "ms 超过限制 " + effectiveMax);
        }
        return BudgetResult.ok();
    }
}
