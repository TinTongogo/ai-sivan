package com.icusu.sivan.core.tool;

import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * 工具提供者
 */
public interface ToolProvider {

    String providerId();

    List<ToolSpec> listTools();

    Mono<ToolResult> execute(String toolName, Map<String, Object> args);
}
