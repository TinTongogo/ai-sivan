package com.icusu.sivan.infra.tool.adapter;

import com.icusu.sivan.domain.tool.McpServerConfig;
import com.icusu.sivan.domain.tool.IMcpServerConfigRepository;
import com.icusu.sivan.infra.shared.security.ApiKeyEncryptor;
import com.icusu.sivan.infra.tool.entity.McpServerConfigEntity;
import com.icusu.sivan.infra.tool.repository.McpServerConfigJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * MCP 服务器配置仓储适配器，实现 IMcpServerConfigRepository。
 */
@Component
@RequiredArgsConstructor
public class McpServerConfigRepositoryAdapter implements IMcpServerConfigRepository {

    private final McpServerConfigJpaRepository jpaRepository;
    private final ApiKeyEncryptor apiKeyEncryptor;

    /**
     * 根据 ID 查询 MCP 服务器配置。
     */
    @Override
    public Optional<McpServerConfig> findById(UUID serverId) {
        return jpaRepository.findById(serverId).map(this::toDomain);
    }

    /**
     * 查询账号下所有 MCP 服务器配置。
     */
    @Override
    public List<McpServerConfig> findAllByAccount(UUID accountId) {
        return jpaRepository.findByAccountId(accountId).stream()
                .map(this::toDomain).toList();
    }

    /**
     * 查询账号下已激活的 MCP 服务器配置。
     */
    @Override
    public List<McpServerConfig> findActiveByAccount(UUID accountId) {
        return jpaRepository.findByAccountIdAndActiveTrue(accountId).stream()
                .map(this::toDomain).toList();
    }

    /**
     * 保存 MCP 服务器配置，回写 ID 和时间戳。
     */
    @Override
    public void save(McpServerConfig config) {
        McpServerConfigEntity entity = toEntity(config);
        jpaRepository.save(entity);
        if (config.getServerId() == null) {
            config.setServerId(entity.getServerId());
        }
        config.setCreatedAt(entity.getCreatedAt() != null ? entity.getCreatedAt().toLocalDateTime() : null);
        config.setUpdatedAt(entity.getUpdatedAt() != null ? entity.getUpdatedAt().toLocalDateTime() : null);
    }

    /**
     * 查询所有已激活的 MCP 服务器配置。
     */
    @Override
    public List<McpServerConfig> findAllActive() {
        return jpaRepository.findByActiveTrue().stream()
                .map(this::toDomain).toList();
    }

    /**
     * 根据 ID 删除 MCP 服务器配置。
     */
    @Override
    public void delete(UUID serverId) {
        jpaRepository.deleteById(serverId);
    }

    /**
     * 将实体转换为领域对象。
     */
    private McpServerConfig toDomain(McpServerConfigEntity entity) {
        return McpServerConfig.builder()
                .serverId(entity.getServerId())
                .accountId(entity.getAccountId())
                .name(entity.getName())
                .serverUrl(entity.getServerUrl())
                .apiKey(apiKeyEncryptor.decrypt(entity.getApiKey()))
                .transport(entity.getTransport())
                .active(entity.getActive())
                .createdAt(entity.getCreatedAt() != null ? entity.getCreatedAt().toLocalDateTime() : null)
                .updatedAt(entity.getUpdatedAt() != null ? entity.getUpdatedAt().toLocalDateTime() : null)
                .build();
    }

    /**
     * 将领域对象转换为实体。
     */
    private McpServerConfigEntity toEntity(McpServerConfig config) {
        McpServerConfigEntity entity = new McpServerConfigEntity();
        entity.setServerId(config.getServerId());
        entity.setAccountId(config.getAccountId());
        entity.setName(config.getName());
        entity.setServerUrl(config.getServerUrl());
        entity.setApiKey(apiKeyEncryptor.encrypt(config.getApiKey()));
        entity.setTransport(config.getTransport() != null ? config.getTransport() : "sse");
        entity.setActive(config.getActive() != null ? config.getActive() : true);
        return entity;
    }
}
