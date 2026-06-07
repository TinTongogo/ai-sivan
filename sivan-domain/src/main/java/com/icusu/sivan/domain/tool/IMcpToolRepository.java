package com.icusu.sivan.domain.tool;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * MCP 工具仓储接口。
 */
public interface IMcpToolRepository {

    /** 保存工具，回写 ID。 */
    void save(McpTool tool);

    /** 批量保存，回写 ID。 */
    void saveAll(List<McpTool> tools);

    /** 根据 ID 查找。 */
    Optional<McpTool> findById(UUID toolId);

    /** 查询指定服务器的所有工具。 */
    List<McpTool> findByServerId(UUID serverId);

    /** 根据名称和服务器 ID 查找。 */
    Optional<McpTool> findByNameAndServerId(String name, UUID serverId);

    /** 删除指定服务器的所有工具。 */
    void deleteByServerId(UUID serverId);

    /** 查询所有工具。 */
    List<McpTool> findAll();
}
