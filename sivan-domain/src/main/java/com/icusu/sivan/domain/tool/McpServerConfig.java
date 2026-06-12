package com.icusu.sivan.domain.tool;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * MCP 服务器配置实体（07-工具动态感知 §5.1）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class McpServerConfig {

    private UUID serverId;
    private UUID accountId;
    private String name;
    private String serverUrl;
    @JsonIgnore
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String apiKey;
    /** 传输协议：SSE / streamable-http / stdio */
    private String transport;
    private Boolean active;
    /** 连接状态：DISCONNECTED / CONNECTING / CONNECTED / ERROR */
    private String connectionStatus;
    /** 最后错误信息 */
    private String lastError;
    /** 最后连接时间 */
    private LocalDateTime lastConnectedAt;
    /** 工具数量（缓存上次连接时的工具统计） */
    private Integer toolCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public void updateFrom(String name, String serverUrl, String apiKey, String transport, Boolean active) {
        if (name != null) this.name = name;
        if (serverUrl != null) this.serverUrl = serverUrl;
        if (apiKey != null) this.apiKey = apiKey;
        if (transport != null) this.transport = transport;
        if (active != null) this.active = active;
        this.updatedAt = LocalDateTime.now();
    }
}
