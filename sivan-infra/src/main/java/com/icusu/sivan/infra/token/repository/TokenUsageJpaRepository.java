package com.icusu.sivan.infra.token.repository;

import com.icusu.sivan.infra.token.entity.TokenUsageEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Token 用量表数据访问接口。
 */
@Repository
public interface TokenUsageJpaRepository extends JpaRepository<TokenUsageEntity, UUID> {

    @Query(value = """
            SELECT COALESCE(SUM(input_tokens), 0), COALESCE(SUM(output_tokens), 0)
            FROM token_usage
            WHERE account_id = :accountId AND created_at >= :since
            """, nativeQuery = true)
    List<Object[]> sumSince(@Param("accountId") UUID accountId, @Param("since") LocalDateTime since);

    @Query(value = """
            SELECT
                EXTRACT(HOUR FROM created_at) * 2 + FLOOR(EXTRACT(MINUTE FROM created_at) / 30) AS bucket,
                COALESCE(SUM(input_tokens), 0) AS total_input,
                COALESCE(SUM(output_tokens), 0) AS total_output
            FROM token_usage
            WHERE account_id = :accountId AND created_at >= CAST(:date AS timestamptz)
              AND created_at < (CAST(:date AS timestamptz) + INTERVAL '1 day')
            GROUP BY bucket
            ORDER BY bucket
            """, nativeQuery = true)
    List<Object[]> dailyTrend(@Param("accountId") UUID accountId, @Param("date") LocalDate date);

    @Query(value = """
            SELECT agent_id, COALESCE(SUM(input_tokens), 0), COALESCE(SUM(output_tokens), 0)
            FROM token_usage
            WHERE account_id = :accountId AND created_at >= :since
            GROUP BY agent_id
            ORDER BY SUM(input_tokens + output_tokens) DESC
            """, nativeQuery = true)
    List<Object[]> sumByAgentSince(@Param("accountId") UUID accountId, @Param("since") LocalDateTime since);

    @Query(value = """
            SELECT model_name, COALESCE(SUM(input_tokens), 0), COALESCE(SUM(output_tokens), 0)
            FROM token_usage
            WHERE account_id = :accountId AND created_at >= :since
            GROUP BY model_name
            ORDER BY SUM(input_tokens + output_tokens) DESC
            """, nativeQuery = true)
    List<Object[]> sumByModelSince(@Param("accountId") UUID accountId, @Param("since") LocalDateTime since);

    @Query(value = """
            SELECT DATE(created_at) AS day,
                   COALESCE(SUM(input_tokens), 0) AS total_input,
                   COALESCE(SUM(output_tokens), 0) AS total_output
            FROM token_usage
            WHERE account_id = :accountId AND created_at >= :since
            GROUP BY DATE(created_at)
            ORDER BY day
            """, nativeQuery = true)
    List<Object[]> dailyConsumption(@Param("accountId") UUID accountId, @Param("since") LocalDateTime since);
}
