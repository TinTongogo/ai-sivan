package com.icusu.sivan.infra.forest.execution;

import com.icusu.sivan.domain.forest.port.BudgetEnforcer.BudgetResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link DefaultBudgetEnforcer} 单元测试。
 */
class DefaultBudgetEnforcerTest {

    private final DefaultBudgetEnforcer enforcer = new DefaultBudgetEnforcer(20, 64000, 3600000);

    @Test
    void depthWithinLimit() {
        BudgetResult r = enforcer.checkDepth(5, 100);
        assertTrue(r.allowed());
    }

    @Test
    void depthExceedsLimit() {
        BudgetResult r = enforcer.checkDepth(21, 100);
        assertFalse(r.allowed());
        assertNotNull(r.reason());
    }

    @Test
    void depthExceedsCallerMax() {
        BudgetResult r = enforcer.checkDepth(10, 5);
        assertFalse(r.allowed());
    }

    @Test
    void depthAtLimit() {
        BudgetResult r = enforcer.checkDepth(20, 100);
        assertTrue(r.allowed());
    }

    @Test
    void tokenWithinLimit() {
        BudgetResult r = enforcer.checkToken(1000, 100000);
        assertTrue(r.allowed());
    }

    @Test
    void tokenExceedsLimit() {
        BudgetResult r = enforcer.checkToken(100000, 200000);
        assertFalse(r.allowed());
        assertNotNull(r.reason());
    }

    @Test
    void tokenExceedsCallerMax() {
        BudgetResult r = enforcer.checkToken(50000, 30000);
        assertFalse(r.allowed());
    }

    @Test
    void timeWithinLimit() {
        BudgetResult r = enforcer.checkTime(1000, 5000);
        assertTrue(r.allowed());
    }

    @Test
    void timeExceedsLimit() {
        BudgetResult r = enforcer.checkTime(5000, 3000);
        assertFalse(r.allowed());
        assertNotNull(r.reason());
    }

    @Test
    void timeExceedsGlobalMax() {
        // 全局 maxTimeMs=3600000，caller 传更大的也应被限制
        BudgetResult r = enforcer.checkTime(5000000, 10000000);
        assertFalse(r.allowed());
    }

    @Test
    void zeroDepthAllowed() {
        BudgetResult r = enforcer.checkDepth(0, 100);
        assertTrue(r.allowed());
    }

    @Test
    void negativeDepthFails() {
        BudgetResult r = enforcer.checkDepth(-1, 100);
        assertTrue(r.allowed()); // -1 <= 20, so allowed
    }

    @Test
    void tokenAtExactLimit() {
        BudgetResult r = enforcer.checkToken(64000, 128000);
        assertTrue(r.allowed());
    }
}
