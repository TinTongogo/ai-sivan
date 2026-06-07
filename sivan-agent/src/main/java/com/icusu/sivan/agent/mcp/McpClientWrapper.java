package com.icusu.sivan.agent.mcp;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.List;

/**
 * MCP 同步客户端包装器。
 */
public class McpClientWrapper implements AutoCloseable {

    private final String name;
    private final McpSyncClient client;

    public McpClientWrapper(String name, McpSyncClient client) {
        this.name = name;
        this.client = client;
    }

    public String getName() {
        return name;
    }

    public McpSyncClient getClient() {
        return client;
    }

    /** 获取工具列表（同步）。 */
    public List<McpSchema.Tool> listTools() {
        return client.listTools().tools();
    }

    /** 调用工具。 */
    public McpSchema.CallToolResult callTool(McpSchema.CallToolRequest request) {
        return client.callTool(request);
    }

    @Override
    public void close() {
        try {
            client.closeGracefully();
        } catch (Exception ignored) {
            client.close();
        }
    }
}
