package com.icusu.sivan.domain.routing;

/**
 * Beta 分布参数 — 在线贝叶斯统计的核心数据结构。
 * <p>
 * Beta(α, β) 的期望 = α/(α+β)，方差 = αβ/((α+β)²(α+β+1))。
 * α = 成功次数 + 1（先验），β = 失败次数 + 1（先验）。
 */
public record BetaParam(
        String agentName,
        int alpha,
        int beta,
        double expectation
) {
    public static BetaParam of(String agentName, int alpha, int beta) {
        double exp = (double) alpha / (alpha + beta);
        return new BetaParam(agentName, alpha, beta, exp);
    }

    /** 先验参数（无历史数据时使用）。 */
    public static BetaParam prior(String agentName) {
        return of(agentName, 1, 1);
    }
}
