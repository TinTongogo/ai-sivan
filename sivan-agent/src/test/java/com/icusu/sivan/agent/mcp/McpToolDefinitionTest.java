package com.icusu.sivan.agent.mcp;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class McpToolDefinitionTest {

    @Test
    void simpleSchema_shouldCreateObjectSchema() {
        Map<String, String> props = Map.of("name", "string", "count", "integer");
        ObjectNode schema = McpToolDefinition.simpleSchema(props);

        assertEquals("object", schema.get("type").asText());
        assertTrue(schema.has("properties"));
        assertEquals("string", schema.get("properties").get("name").get("type").asText());
        assertEquals("integer", schema.get("properties").get("count").get("type").asText());
    }

    @Test
    void simpleSchema_shouldHandleEmptyProps() {
        ObjectNode schema = McpToolDefinition.simpleSchema(Map.of());
        assertEquals("object", schema.get("type").asText());
        assertTrue(schema.get("properties").isEmpty());
    }

    @Test
    void builder_shouldBuildToolDefinition() {
        McpToolDefinition tool = McpToolDefinition.builder()
                .name("test-tool")
                .description("A test tool")
                .inputSchema(McpToolDefinition.simpleSchema(Map.of("arg", "string")))
                .build();

        assertEquals("test-tool", tool.getName());
        assertEquals("A test tool", tool.getDescription());
        assertNotNull(tool.getInputSchema());
        assertNull(tool.getHandler()); // 未设置时为 null
    }
}
