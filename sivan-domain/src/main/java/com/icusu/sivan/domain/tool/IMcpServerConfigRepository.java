package com.icusu.sivan.domain.tool;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * MCP 服务器配置仓储接口。
 */
public interface IMcpServerConfigRepository {

    /** 根据 ID 查找 MCP 服务器配置。 */
    Optional<McpServerConfig> findById(UUID serverId);

    /** 获取指定用户的所有 MCP 服务器配置。 */
    List<McpServerConfig> findAllByAccount(UUID accountId);

    /** 获取指定用户的活跃 MCP 服务器配置。 */
    List<McpServerConfig> findActiveByAccount(UUID accountId);

    /** 跨账号获取所有 active 配置（启动时自动连接用） */
    List<McpServerConfig> findAllActive();

    /** 保存 MCP 服务器配置。 */
    void save(McpServerConfig config);

    /** 删除 MCP 服务器配置。 */
    void delete(UUID serverId);
}
