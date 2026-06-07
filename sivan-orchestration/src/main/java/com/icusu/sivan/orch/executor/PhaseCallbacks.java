package com.icusu.sivan.orch.executor;

/**
 * PhaseExecutor 的回调复合接口（ISP 拆分后为三接口组合）。
 *
 * <p>继承 {@link LlmStrategy} / {@link ExecutionLifecycle} / {@link PersistenceStrategy}。
 * 新代码可按需依赖窄接口，无需实现全部 15 个方法。
 */
public interface PhaseCallbacks extends LlmStrategy, ExecutionLifecycle, PersistenceStrategy {
}
