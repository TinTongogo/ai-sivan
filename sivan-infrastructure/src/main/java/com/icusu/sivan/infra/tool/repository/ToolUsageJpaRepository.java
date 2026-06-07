package com.icusu.sivan.infra.tool.repository;

import com.icusu.sivan.infra.tool.entity.ToolUsageEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/**
 * 工具使用记录 JPA 仓库。
 */
public interface ToolUsageJpaRepository extends JpaRepository<ToolUsageEntity, UUID> {

    @Query(value = """
            SELECT tool_name, COUNT(*) AS cnt
            FROM tool_usage
            WHERE account_id = :accountId
            GROUP BY tool_name
            ORDER BY cnt DESC
            LIMIT 100
            """, nativeQuery = true)
    List<Object[]> countByToolName(@Param("accountId") UUID accountId);

    @Query(value = """
            SELECT tool_name, COUNT(*) AS cnt
            FROM tool_usage
            WHERE account_id = :accountId AND agent_name = :agentName
            GROUP BY tool_name
            ORDER BY cnt DESC
            LIMIT 100
            """, nativeQuery = true)
    List<Object[]> countByToolNameAndAgent(@Param("accountId") UUID accountId,
                                           @Param("agentName") String agentName);
}
