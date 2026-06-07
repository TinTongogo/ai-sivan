package com.icusu.sivan.common.enums;

/** Token 用量来源分类：CHAT = 对话 LLM 调用，ROUTING = 路由/分类决策 LLM 调用，SQUAD_EXECUTION = Squad 执行中 Agent LLM 调用，GOAL = 自主目标执行中 Agent LLM 调用。 */
public enum TokenSource {
    CHAT,
    ROUTING,
    SQUAD_EXECUTION,
    GOAL
}
