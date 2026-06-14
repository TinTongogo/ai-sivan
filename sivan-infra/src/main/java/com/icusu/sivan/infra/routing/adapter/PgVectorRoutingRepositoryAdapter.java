package com.icusu.sivan.infra.routing.adapter;

import com.icusu.sivan.domain.routing.PgVectorRoutingRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * pgvector 语义检索适配器 — HNSW 索引加速。
 */
@Component
public class PgVectorRoutingRepositoryAdapter implements PgVectorRoutingRepository {

    private final JdbcTemplate jdbc;

    public PgVectorRoutingRepositoryAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<SimilarRoute> findSimilar(UUID accountId, float[] embedding, int k) {
        // 将 float[] 转换为 pgvector 格式 '[0.1,0.2,...]'
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(embedding[i]);
        }
        sb.append("]");
        String vecStr = sb.toString();

        return jdbc.query(
                "WITH beta AS (" +
                "  SELECT agent_name, alpha, beta FROM account_beta_params WHERE account_id = ?" +
                ") " +
                "SELECT r.selected_agent, r.success, " +
                "       1 - (r.task_embedding <=> ?::vector) AS sim, " +
                "       COALESCE(b.alpha, 1) AS alpha, " +
                "       COALESCE(b.beta, 1) AS beta " +
                "FROM routing_decisions r " +
                "LEFT JOIN beta b ON b.agent_name = r.selected_agent " +
                "WHERE r.account_id = ? AND r.task_embedding IS NOT NULL " +
                "ORDER BY r.task_embedding <=> ?::vector " +
                "LIMIT ?",
                (rs, row) -> new SimilarRoute(
                        rs.getString("selected_agent"),
                        rs.getBoolean("success"),
                        rs.getDouble("sim"),
                        rs.getInt("alpha"),
                        rs.getInt("beta")),
                accountId, vecStr, accountId, vecStr, k);
    }
}
