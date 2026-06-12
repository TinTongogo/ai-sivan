package com.icusu.sivan.domain.routing;

import java.util.List;
import java.util.UUID;

/**
 * pgvector 路由仓储 — Tier 1 语义检索。
 */
public interface PgVectorRoutingRepository {

    /**
     * 语义检索最相似的 K 条历史路由决策。
     *
     * @param accountId  账户 ID
     * @param embedding 任务 embedding（float[384]）
     * @param k          返回前 K 条
     * @return 相似历史记录（含相似度）
     */
    List<SimilarRoute> findSimilar(UUID accountId, float[] embedding, int k);

    record SimilarRoute(
            String agentName,
            boolean success,
            double similarity,
            int alpha,
            int beta
    ) {
        public double expectation() {
            return (double) alpha / (alpha + beta);
        }
    }
}
