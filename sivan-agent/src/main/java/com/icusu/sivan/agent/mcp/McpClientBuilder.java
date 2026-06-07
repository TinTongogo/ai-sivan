package com.icusu.sivan.agent.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpClientTransport;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;

/**
 * MCP 客户端构建器。
 * <p>
 * 支持 SSE 和 Streamable HTTP 两种传输协议。
 */
@Slf4j
public class McpClientBuilder {

    private final String name;
    private Duration timeout = Duration.ofSeconds(60);
    private String sseUrl;
    private String streamableHttpUrl;
    private String headerKey;
    private String headerValue;

    private McpClientBuilder(String name) {
        this.name = name;
    }

    public static McpClientBuilder create(String name) {
        return new McpClientBuilder(name);
    }

    public McpClientBuilder timeout(Duration timeout) {
        this.timeout = timeout;
        return this;
    }

    public McpClientBuilder sseTransport(String url) {
        this.sseUrl = url;
        return this;
    }

    public McpClientBuilder streamableHttpTransport(String url) {
        this.streamableHttpUrl = url;
        return this;
    }

    public McpClientBuilder header(String key, String value) {
        this.headerKey = key;
        this.headerValue = value;
        return this;
    }

    /**
     * 同步构建 MCP 客户端，三步完成：创建传输 → 构建客户端 → 初始化。
     */
    public McpClientWrapper buildSync() {
        McpClientTransport transport = buildTransport();
        var syncClient = McpClient.sync(transport)
                .requestTimeout(timeout)
                .build();
        try {
            syncClient.initialize();
        } catch (Exception e) {
            syncClient.close();
            closeTransport(transport);
            throw e;
        }
        log.debug("MCP 客户端已构建并初始化: {}", name);
        return new McpClientWrapper(name, syncClient);
    }

    /** 关闭传输层，清理可能残留的 reactive 流（避免 onErrorDropped）。 */
    private static void closeTransport(McpClientTransport transport) {
        try {
            if (transport instanceof java.io.Closeable c) {
                c.close();
            } else if (transport instanceof java.lang.AutoCloseable c) {
                c.close();
            }
        } catch (Exception ignored) {
            // 传输层关闭异常不影响主流程
        }
    }

    private McpClientTransport buildTransport() {
        if (streamableHttpUrl != null) {
            return buildStreamableHttpTransport();
        }
        return buildSseTransport();
    }

    private McpClientTransport buildSseTransport() {
        var builder = HttpClientSseClientTransport.builder(sseUrl != null ? sseUrl : streamableHttpUrl)
                .connectTimeout(timeout);
        if (headerKey != null && headerValue != null) {
            String key = headerKey;
            String value = headerValue;
            builder.customizeRequest(req -> req.header(key, value));
        }
        return builder.build();
    }

    private McpClientTransport buildStreamableHttpTransport() {
        var builder = HttpClientStreamableHttpTransport.builder(streamableHttpUrl);
        if (headerKey != null && headerValue != null) {
            String key = headerKey;
            String value = headerValue;
            builder.customizeRequest(req -> req.header(key, value));
        }
        return builder.build();
    }
}
