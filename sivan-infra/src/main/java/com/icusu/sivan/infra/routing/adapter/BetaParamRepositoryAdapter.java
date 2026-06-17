package com.icusu.sivan.infra.routing.adapter;

import com.icusu.sivan.domain.routing.BetaParam;
import com.icusu.sivan.domain.routing.IBetaParamRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Beta 参数仓储适配器 — 基于 JDBC 模板的 UPSERT 实现。
 * 使用原生 SQL 避免 JPA 对 pgvector 和 UPSERT 的兼容问题。
 */
@Component
public class BetaParamRepositoryAdapter implements IBetaParamRepository {

    private final JdbcTemplate jdbc;

    public BetaParamRepositoryAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<BetaParam> findByKey(UUID accountId, String featureHash, String agentName) {
        var rows = jdbc.query(
                "SELECT agent_name, alpha, beta FROM account_beta_params WHERE account_id = ? AND feature_hash = ? AND agent_name = ?",
                (rs, row) -> BetaParam.of(rs.getString("agent_name"), rs.getInt("alpha"), rs.getInt("beta")),
                accountId, featureHash, agentName);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    @Override
    public List<BetaParam> findAllByKey(UUID accountId, String featureHash) {
        return jdbc.query(
                "SELECT agent_name, alpha, beta FROM account_beta_params WHERE account_id = ? AND feature_hash = ?",
                (rs, row) -> BetaParam.of(rs.getString("agent_name"), rs.getInt("alpha"), rs.getInt("beta")),
                accountId, featureHash);
    }

    @Override
    public void deleteByAgent(UUID accountId, String agentName) {
        jdbc.update("DELETE FROM account_beta_params WHERE account_id = ? AND agent_name = ?",
                accountId, agentName);
    }

    @Override
    public void upsert(UUID accountId, String featureHash, String agentName, boolean success) {
        jdbc.update(
                "INSERT INTO account_beta_params (account_id, feature_hash, agent_name, alpha, beta) VALUES (?, ?, ?, ?, ?) " +
                "ON CONFLICT (account_id, feature_hash, agent_name) DO UPDATE SET " +
                "alpha = account_beta_params.alpha + ?, " +
                "beta  = account_beta_params.beta  + ?, " +
                "updated_at = NOW()",
                accountId, featureHash, agentName,
                success ? 2 : 1, success ? 1 : 2,   // initial: (2,1) or (1,2)
                success ? 1 : 0, success ? 0 : 1);    // increment
    }
}
