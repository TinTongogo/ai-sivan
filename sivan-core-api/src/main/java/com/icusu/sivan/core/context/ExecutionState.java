package com.icusu.sivan.core.context;

/**
 * 执行生命周期状态。
 */
public enum ExecutionState {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    PAUSED,
    CANCELLED
}
