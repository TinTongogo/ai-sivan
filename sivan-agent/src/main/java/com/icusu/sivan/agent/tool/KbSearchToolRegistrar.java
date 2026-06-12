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

                    UUID accountId = ctx != null ? resolveAccountId(ctx.attribute("_accountId")) : null;

                    // 确定可搜索的知识库列表
                    List<String> kbNames = null;
                    String llmSpecifiedKb = (String) args.get("kbName");

                    // 获取对话上下文已选知识库
                    String convKbStr = ctx != null ? ctx.attribute("_kbNames") : null;
                    List<String> allowedKbs = convKbStr != null && !convKbStr.isEmpty()
                            ? List.of(convKbStr.split(","))
                            : null;

                    if (llmSpecifiedKb != null) {
                        // LLM 指定了知识库 → 验证是否在对话已选范围内
                        if (allowedKbs != null && !allowedKbs.contains(llmSpecifiedKb)) {
                            return Mono.just(ToolResult.success("kb_search",
                                    "未启用知识库「" + llmSpecifiedKb + "」，请在右侧工具栏中选择"));
                        }
                        kbNames = List.of(llmSpecifiedKb);
                    } else if (allowedKbs != null && !allowedKbs.isEmpty()) {
                        // LLM 未指定 → 搜索对话已选的全部知识库
                        kbNames = allowedKbs;
                    } else {
                        // 对话未选择任何知识库 → 不搜索
                        return Mono.just(ToolResult.success("kb_search",
                                "当前对话未启用任何知识库，请在右侧工具栏中选择后再搜索"));
                    }

                    int topK = args.get("topK") instanceof Number n ? n.intValue() : 5;
                    final List<String> finalKbNames = kbNames;

                    return Mono.fromCallable(() -> {
                        String result = ragRetrievalPort.retrieveContext(query, finalKbNames, accountId);
                        if (result == null || result.isBlank()) {
                            return ToolResult.success("kb_search", "知识库中未找到相关内容");
                        }
                        return ToolResult.success("kb_search", result);
                    });
                });

        log.info("kb_search 工具注册完成");
    }

    /** 从上下文的 _accountId 属性中解析 UUID（兼容 String 和 UUID 两种存储类型）。 */
    private static UUID resolveAccountId(Object raw) {
        if (raw instanceof UUID u) return u;
        if (raw instanceof String s && !s.isEmpty()) {
            try { return UUID.fromString(s); } catch (Exception ignored) {}
        }
        return null;
    }
}
