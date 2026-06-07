package com.icusu.sivan.common.enums;

/** 消息状态：PENDING = 等待中，RUNNING = LLM 流式生成中，COMPLETED = 生成完成，FAILED = 生成失败，CANCELLED = 已取消（客户端断开/主动取消）。 */
public enum MessageStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED
}
