package com.icusu.sivan.infra.tool.entity;

import com.icusu.sivan.infra.shared.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Map;
import java.util.UUID;

/**
 * mcp_tools 表 JPA 实体，存储 MCP 工具的完整信息。
 */
@Entity
@Table(name = "mcp_tools")
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class McpToolEntity extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID toolId;

    @Column(name = "server_id", nullable = false)
    private UUID serverId;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(length = 256)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "input_schema", columnDefinition = "JSONB")
    private Map<String, Object> inputSchema;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "output_schema", columnDefinition = "JSONB")
    private Map<String, Object> outputSchema;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "JSONB")
    private Map<String, Object> annotations;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "JSONB")
    private Map<String, Object> meta;
}
