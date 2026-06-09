package com.icusu.sivan.agent.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.icusu.sivan.domain.knowledge.KnowledgeBase;
import com.icusu.sivan.domain.knowledge.IKnowledgeBaseRepository;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP Server，对外暴露 Sivan 工具能力。
 * 支持 tools/list 和 tools/call 方法，使用 SSE 传输（在 Controller 层处理）。
 */
@Slf4j
@Component
public class McpServer {

    private final Map<String, McpToolDefinition> tools = new ConcurrentHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();

    private final IKnowledgeBaseRepository knowledgeBaseRepository;

    public McpServer(IKnowledgeBaseRepository knowledgeBaseRepository) {
        this.knowledgeBaseRepository = knowledgeBaseRepository;
    }

    /**
     * 初始化，注册内置工具。
     */
    @PostConstruct
    public void init() {
        registerTool(McpToolDefinition.builder()
                .name("list_knowledge_bases")
                .description("列出当前用户的所有知识库")
                .inputSchema(McpToolDefinition.simpleSchema(Map.of()))
                .handler((params, accountId) -> {
                    List<KnowledgeBase> kbs = knowledgeBaseRepository.findAllByAccount(accountId);
                    try {
                        return Mono.just(mapper.writeValueAsString(kbs.stream()
                                .map(kb -> Map.of(
                                        "kbName", kb.getKbName(),
                                        "description", kb.getDescription() != null ? kb.getDescription() : ""
                                )).toList()));
                    } catch (Exception e) {
                        return Mono.error(e);
                    }
                })
                .build());

        log.info("MCP Server 初始化完成，已注册 {} 个工具", tools.size());
    }

    /**
     * 注册工具。
     */
    public void registerTool(McpToolDefinition tool) {
        tools.put(tool.getName(), tool);
        log.debug("MCP 注册工具: {}", tool.getName());
    }

    /**
     * 处理 tools/list 请求。
     */
    public ObjectNode handleListTools() {
        ArrayNode toolsArray = mapper.createArrayNode();
        for (McpToolDefinition tool : tools.values()) {
            ObjectNode toolNode = toolsArray.addObject();
            toolNode.put("name", tool.getName());
            toolNode.put("description", tool.getDescription());
            toolNode.set("inputSchema", tool.getInputSchema());
        }

        ObjectNode result = mapper.createObjectNode();
        result.set("tools", toolsArray);
        return result;
    }

    /**
     * 处理 tools/call 请求。
     */
    public Mono<ObjectNode> handleCallTool(String toolName, Map<String, Object> arguments, UUID accountId) {
        McpToolDefinition tool = tools.get(toolName);
        if (tool == null) {
            ObjectNode error = mapper.createObjectNode();
            error.put("content", "未知工具: " + toolName);
            error.put("isError", true);
            return Mono.just(error);
        }

        return tool.getHandler().apply(arguments != null ? arguments : Map.of(), accountId)
                .map(content -> {
                    ObjectNode result = mapper.createObjectNode();
                    result.put("content", content);
                    return result;
                })
                .onErrorResume(e -> {
                    log.error("MCP 工具调用失败: {}", toolName, e);
                    ObjectNode error = mapper.createObjectNode();
                    error.put("content", "工具调用异常: " + e.getMessage());
                    error.put("isError", true);
                    return Mono.just(error);
                });
    }

    /**
     * 获取所有注册的工具名称。
     */
    public Set<String> getToolNames() {
        return tools.keySet();
    }
}
