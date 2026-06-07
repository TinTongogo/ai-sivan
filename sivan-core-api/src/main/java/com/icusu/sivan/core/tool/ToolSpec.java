package com.icusu.sivan.core.tool;

import java.util.Map;

public record ToolSpec(String name, String description, Map<String, Object> inputSchema) {
    public ToolSpec {
        inputSchema = inputSchema == null ? Map.of() : Map.copyOf(inputSchema);
    }
}
