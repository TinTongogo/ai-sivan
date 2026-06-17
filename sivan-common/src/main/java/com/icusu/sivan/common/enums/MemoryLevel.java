package com.icusu.sivan.common.enums;

/** 记忆层级（递进：SESSION → PROJECT → USER）：SESSION = 对话级（5h），PROJECT = 项目级（7d），USER = 用户级（90d）。 */
public enum MemoryLevel {
    SESSION,
    PROJECT,
    USER
}
