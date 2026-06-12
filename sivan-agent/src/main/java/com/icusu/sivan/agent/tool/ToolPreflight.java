package com.icusu.sivan.agent.tool;

import com.icusu.sivan.agent.mcp.McpConnectionManager;
import com.icusu.sivan.agent.mcp.McpClientWrapper;
import com.icusu.sivan.core.tool.ToolProvider;
import com.icusu.sivan.core.tool.ToolRegistry;
import com.icusu.sivan.domain.tool.PreflightResult;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * MCP 工具预检组件（07-工具动态感知 §4.5）。
 * <p>
 * 在 MCP 服务器按需连接后、工具执行前检查可用性。
 * 有问题提前告知用户，避免执行中报错。
 */
@Component
public class ToolPreflight {

    private static final Logger log = LoggerFactory.getLogger(ToolPreflight.class);

    private final McpConnectionManager connectionManager;
    private final ToolRegistry registry;

    public ToolPreflight(McpConnectionManager connectionManager, ToolRegistry registry) {
        this.connectionManager = connectionManager;
        this.registry = registry;
    }

    /**
     * 预检指定服务器：检查可用性 + 凭证有效性。
     */
    public Mono<List<PreflightResult>> check(String serverId) {
        UUID sid;
        try { sid = UUID.fromString(serverId); } catch (Exception e) {
            return Mono.just(List.of(new PreflightResult(serverId, "*", false, "无效的服务器 ID")));
        }

        var clientOpt = connectionManager.getClient(sid);
        if (clientOpt.isEmpty()) {
            return Mono.just(List.of(new PreflightResult(serverId, "*", false, "MCP 服务器未连接")));
        }

        McpClientWrapper client = clientOpt.get();
        if (!client.isConnected()) {
            return Mono.just(List.of(new PreflightResult(serverId, "*", false, "MCP 服务器连接已断开")));
        }

        return Mono.fromCallable(() -> {
            List<McpSchema.Tool> tools = client.listTools();
            return tools.stream()
                    .map(tool -> {
                        boolean ok = client.isConnected();
                        return new PreflightResult(
                                serverId,
                                tool.name(),
                                ok,
                                ok ? null : "MCP 服务器连接已断开",
                                true
                        );
                    })
                    .toList();
        })
        .subscribeOn(Schedulers.boundedElastic())
        .onErrorResume(e -> {
            log.warn("ToolPreflight: 预检失败 serverId={} error={}", serverId, e.getMessage());
            return Mono.just(List.of(
                    new PreflightResult(serverId, "*", false, "预检异常: " + e.getMessage(), false)
            ));
        });
    }
}
