package com.icusu.sivan.infra.tool.repository;

import com.icusu.sivan.infra.tool.entity.McpServerConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * MCP 服务器配置表数据访问接口。
 */
@Repository
public interface McpServerConfigJpaRepository extends JpaRepository<McpServerConfigEntity, UUID> {

    List<McpServerConfigEntity> findByAccountId(UUID accountId);

    List<McpServerConfigEntity> findByAccountIdAndActiveTrue(UUID accountId);

    List<McpServerConfigEntity> findByActiveTrue();
}
