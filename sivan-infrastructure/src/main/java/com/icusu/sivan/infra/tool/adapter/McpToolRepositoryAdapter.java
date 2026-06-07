package com.icusu.sivan.infra.tool.adapter;

import com.icusu.sivan.domain.tool.McpTool;
import com.icusu.sivan.domain.tool.IMcpToolRepository;
import com.icusu.sivan.infra.tool.entity.McpToolEntity;
import com.icusu.sivan.infra.tool.repository.McpToolJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * MCP 工具仓储适配器，实现 IMcpToolRepository。
 */
@Component
@RequiredArgsConstructor
public class McpToolRepositoryAdapter implements IMcpToolRepository {

    private final McpToolJpaRepository jpaRepository;

    @Override
    public void save(McpTool tool) {
        McpToolEntity entity = toEntity(tool);
        jpaRepository.save(entity);
        if (tool.getToolId() == null) {
            tool.setToolId(entity.getToolId());
        }
        tool.setCreatedAt(entity.getCreatedAt() != null ? entity.getCreatedAt().toLocalDateTime() : null);
        tool.setUpdatedAt(entity.getUpdatedAt() != null ? entity.getUpdatedAt().toLocalDateTime() : null);
    }

    @Override
    @Transactional
    public void saveAll(List<McpTool> tools) {
        List<McpToolEntity> entities = tools.stream().map(this::toEntity).toList();
        jpaRepository.saveAll(entities);
        for (int i = 0; i < tools.size(); i++) {
            McpTool tool = tools.get(i);
            McpToolEntity entity = entities.get(i);
            if (tool.getToolId() == null) {
                tool.setToolId(entity.getToolId());
            }
        }
    }

    @Override
    public Optional<McpTool> findById(UUID toolId) {
        return jpaRepository.findById(toolId).map(this::toDomain);
    }

    @Override
    public List<McpTool> findByServerId(UUID serverId) {
        return jpaRepository.findByServerId(serverId).stream()
                .map(this::toDomain).toList();
    }

    @Override
    public Optional<McpTool> findByNameAndServerId(String name, UUID serverId) {
        return jpaRepository.findByNameAndServerId(name, serverId).map(this::toDomain);
    }

    @Override
    @Transactional
    public void deleteByServerId(UUID serverId) {
        jpaRepository.deleteByServerId(serverId);
    }

    @Override
    public List<McpTool> findAll() {
        return jpaRepository.findAll().stream()
                .map(this::toDomain).toList();
    }

    private McpTool toDomain(McpToolEntity entity) {
        return McpTool.builder()
                .toolId(entity.getToolId())
                .serverId(entity.getServerId())
                .name(entity.getName())
                .title(entity.getTitle())
                .description(entity.getDescription())
                .inputSchema(entity.getInputSchema())
                .outputSchema(entity.getOutputSchema())
                .annotations(entity.getAnnotations())
                .meta(entity.getMeta())
                .createdAt(entity.getCreatedAt() != null ? entity.getCreatedAt().toLocalDateTime() : null)
                .updatedAt(entity.getUpdatedAt() != null ? entity.getUpdatedAt().toLocalDateTime() : null)
                .build();
    }

    private McpToolEntity toEntity(McpTool tool) {
        McpToolEntity entity = new McpToolEntity();
        entity.setToolId(tool.getToolId());
        entity.setServerId(tool.getServerId());
        entity.setName(tool.getName());
        entity.setTitle(tool.getTitle());
        entity.setDescription(tool.getDescription());
        entity.setInputSchema(tool.getInputSchema());
        entity.setOutputSchema(tool.getOutputSchema());
        entity.setAnnotations(tool.getAnnotations());
        entity.setMeta(tool.getMeta());
        return entity;
    }
}
