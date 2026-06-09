package com.icusu.sivan.common;

/**
 * 节点生命周期状态。
 */
public enum NodeStatus {
    /** 等待执行 */
    PENDING,
    /** 执行中 */
    RUNNING,
    /** 执行完成 */
    COMPLETED,
    /** 执行失败 */
    FAILED,
    /** 已取消 */
    CANCELLED,
    /** 已折叠（压缩后） */
    FOLDED
}
