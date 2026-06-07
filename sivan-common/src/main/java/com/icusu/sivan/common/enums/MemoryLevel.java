package com.icusu.sivan.common.enums;

/** 记忆层级（四级模型）：SESSION = 对话级（1h 保留），USER = 用户级（24h），TEAM = Squad 团队级（7d），PROJECT = 项目级（30d）。 */
public enum MemoryLevel {
    SESSION,
    USER,
    TEAM,
    PROJECT
}
