package com.icusu.sivan.common.enums;

/** Squad 执行状态：PENDING = 等待执行，RUNNING = 执行中，PAUSED = 暂停（HITL 等待审核），COMPLETED = 执行完成，FAILED = 执行失败，CANCELLED = 已取消，TIMEOUT = 超时终止，HITL_PENDING = 等待人工审核。 */
public enum ExecutionStatus {
    PENDING,
    RUNNING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED,
    TIMEOUT,
    HITL_PENDING
}
