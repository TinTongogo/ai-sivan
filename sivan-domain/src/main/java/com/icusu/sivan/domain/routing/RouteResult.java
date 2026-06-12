package com.icusu.sivan.domain.routing;

/**
 * 路由决策结果。
 *
 * @param agentName  分配的 Agent 名称（chat 路径为 null）
 * @param category   Agent 分类
 * @param tier        路由层级 0/1/2/3
 * @param confidence  置信度
 * @param intent      执行意图："chat" — 轻量对话；"task" — 完整 Agent 执行
 */
public record RouteResult(
        String agentName,
        String category,
        int tier,          // 0/1/2/3 哪层命中
        double confidence,
        String intent      // "chat" | "task"
) {
    /** 兼容旧构造（不含 intent），默认 intent="task" */
    public RouteResult(String agentName, String category, int tier, double confidence) {
        this(agentName, category, tier, confidence, "task");
    }
}
