package com.icusu.sivan.infra.tool.repository;

import com.icusu.sivan.infra.tool.entity.McpToolEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * mcp_tools 表 JPA 数据访问。
 */
public interface McpToolJpaRepository extends JpaRepository<McpToolEntity, UUID> {

    List<McpToolEntity> findByServerId(UUID serverId);

    Optional<McpToolEntity> findByNameAndServerId(String name, UUID serverId);

    @Modifying
    @Query("DELETE FROM McpToolEntity t WHERE t.serverId = :serverId")
    void deleteByServerId(@Param("serverId") UUID serverId);
}
