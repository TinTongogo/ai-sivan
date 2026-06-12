package com.icusu.sivan.infra.tool.entity;

import com.icusu.sivan.infra.shared.entity.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * mcp_server_configs 表 JPA 实体，表示 MCP 服务器连接配置（07-工具动态感知 §5.1）。
 */
@Data
@Entity
@Table(name = "mcp_server_configs")
@EqualsAndHashCode(callSuper = false)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class McpServerConfigEntity extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "server_id")
    private UUID serverId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "name", nullable = false, length = 64)
    private String name;

    @Column(name = "server_url", nullable = false, length = 512)
    private String serverUrl;

    @Column(name = "api_key", length = 512)
    private String apiKey;

    @Column(name = "transport", nullable = false, length = 16)
    @Builder.Default
    private String transport = "sse";

    @Column(name = "active", nullable = false)
    @Builder.Default
    private Boolean active = true;

    /** 连接状态：DISCONNECTED / CONNECTING / CONNECTED / ERROR */
    @Column(name = "connection_status", length = 16)
    private String connectionStatus;

    /** 最后错误信息 */
    @Column(name = "last_error", length = 512)
    private String lastError;

    /** 最后连接时间 */
    @Column(name = "last_connected_at")
    private LocalDateTime lastConnectedAt;

    /** 工具数量 */
    @Column(name = "tool_count")
    private Integer toolCount;
}
