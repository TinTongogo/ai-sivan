package com.icusu.sivan.agent.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.Builder;
import lombok.Data;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;

/**
 * MCP 工具定义，描述一个可被外部 AI 客户端调用的工具。
 */
@Data
@Builder
public class McpToolDefinition {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private String name;
    private String description;
    private ObjectNode inputSchema;
    private BiFunction<Map<String, Object>, UUID, Mono<String>> handler;

    /**
     * 创建一个简单的输入模式（全部为字符串参数）。
     */
    public static ObjectNode simpleSchema(Map<String, String> requiredProps) {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        for (Map.Entry<String, String> entry : requiredProps.entrySet()) {
            ObjectNode prop = properties.putObject(entry.getKey());
            prop.put("type", entry.getValue());
        }
        return schema;
    }
}
