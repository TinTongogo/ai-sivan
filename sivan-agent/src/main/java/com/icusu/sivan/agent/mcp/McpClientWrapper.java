package com.icusu.sivan.agent.mcp;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * MCP 同步客户端包装器。
 */
public class McpClientWrapper implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(McpClientWrapper.class);

    private final String name;
    private final McpSyncClient client;
    private volatile boolean connected = true;

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

    /** 是否连接正常。 */
    public boolean isConnected() {
        return connected;
    }

    /** 健康检查：尝试获取工具列表。成功返回 true，失败标记为断连并返回 false。 */
    public boolean ping() {
        try {
            client.listTools();
            connected = true;
            return true;
        } catch (Exception e) {
            if (connected) {
                log.warn("MCP 客户端心跳失败: name={} error={}", name, e.getMessage());
            }
            connected = false;
            return false;
        }
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
        connected = false;
        try {
            client.closeGracefully();
        } catch (Exception ignored) {
            client.close();
        }
    }
}
