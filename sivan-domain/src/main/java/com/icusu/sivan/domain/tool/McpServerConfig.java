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
 * MCP 服务器配置实体。
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
    private String transport;
    private Boolean active;
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
