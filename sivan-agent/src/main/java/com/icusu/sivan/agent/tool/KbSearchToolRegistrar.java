package com.icusu.sivan.agent.tool;

import com.icusu.sivan.core.context.ExecutionContext;
import com.icusu.sivan.core.tool.ToolResult;
import com.icusu.sivan.core.tool.ToolSpec;
import com.icusu.sivan.domain.shared.port.RagRetrievalPort;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 将知识库搜索注册为工具（kb_search），供 LLM 按需调用。
 * <p>
 * 替代旧的自动注入 RAG 上下文模式，由 LLM 自行判断是否需要查询知识库，
 * 减少不必要的 embedding 调用和 token 消耗。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KbSearchToolRegistrar {

    private final RagRetrievalPort ragRetrievalPort;
    private final ToolRegistryImpl toolRegistry;

    @PostConstruct
    public void init() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("query", Map.of("type", "string", "description", "搜索查询，如「项目的用户模块设计文档」"));
        props.put("kbName", Map.of("type", "string", "description", "可选，知识库名称。不指定时搜索全部可用知识库"));
        props.put("topK", Map.of("type", "integer", "description", "可选，返回结果数，默认 5"));
        schema.put("properties", props);
        schema.put("required", List.of("query"));

        toolRegistry.register(
                new ToolSpec("kb_search",
                        "搜索知识库。当你需要参考项目文档、历史记录或专业知识时使用。"
                                + "支持指定知识库名称（不指定则搜索全部）。查询越具体，结果越精准。",
                        schema),
                (call, ctx) -> {
                    Map<String, Object> args = call.args() != null ? call.args() : Map.of();
                    String query = (String) args.get("query");
                    if (query == null || query.isBlank()) {
                        return Mono.just(ToolResult.failure("kb_search", "query 参数缺失"));
                    }

                    UUID accountId = ctx != null ? ctx.attribute("_accountId") : null;

                    @SuppressWarnings("unchecked")
                    List<String> kbNames = args.get("kbName") != null
                            ? List.of((String) args.get("kbName"))
                            : null;

                    int topK = args.get("topK") instanceof Number n ? n.intValue() : 5;

                    return Mono.fromCallable(() -> {
                        String result = ragRetrievalPort.retrieveContext(query, kbNames, accountId);
                        if (result == null || result.isBlank()) {
                            return ToolResult.success("kb_search", "知识库中未找到相关内容");
                        }
                        return ToolResult.success("kb_search", result);
                    });
                });

        log.info("kb_search 工具注册完成");
    }
}
