package com.icusu.sivan.agent.tool;

import com.icusu.sivan.domain.shared.port.IEmbeddingService;
import com.icusu.sivan.common.util.CosineSimilarity;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 默认工具能力注册表。
 * <p>
 * 基于 Embedding 语义匹配推断 MCP 工具的能力标签，相比关键词匹配大幅提高精度。
 * Embedding 服务不可用时降级为关键词匹配策略。
 */
@Slf4j
@Component
public class DefaultToolCapabilityRegistry implements ToolCapabilityRegistry {

    private final IEmbeddingService embeddingService;
    private final List<CapabilityMatcher> matchers = new CopyOnWriteArrayList<>();

    public DefaultToolCapabilityRegistry(IEmbeddingService embeddingService) {
        this.embeddingService = embeddingService;
    }

    @PostConstruct
    void init() {
        if (embeddingService.isAvailable()) {
            registerMatcher(new SemanticCapabilityMatcher(embeddingService));
            log.info("ToolCapabilityRegistry 初始化完成: 语义匹配策略");
        } else {
            registerMatcher(new KeywordCapabilityMatcher());
            log.warn("ToolCapabilityRegistry 初始化完成: 降级关键词匹配策略 (Embedding 服务未配置)");
        }
    }

    @Override
    public List<String> resolve(McpSchema.Tool tool) {
        if (tool == null) return List.of();
        String name = tool.name() != null ? tool.name() : "";
        String desc = tool.description() != null ? tool.description() : "";

        Set<ToolCapability> capabilities = new LinkedHashSet<>();
        for (CapabilityMatcher matcher : matchers) {
            capabilities.addAll(matcher.match(name, desc));
        }
        return capabilities.stream().map(ToolCapability::label).toList();
    }

    @Override
    public List<List<String>> resolveAll(List<McpSchema.Tool> tools) {
        if (tools.isEmpty()) return List.of();
        // 优先使用语义匹配器的批量方法，一次 embedBatch 完成所有工具的能力匹配
        for (CapabilityMatcher matcher : matchers) {
            if (matcher instanceof SemanticCapabilityMatcher scm) {
                return scm.matchAll(tools);
            }
        }
        // 降级：逐工具 resolve
        return tools.stream().map(this::resolve).toList();
    }

    @Override
    public void registerMatcher(CapabilityMatcher matcher) {
        if (matcher != null) {
            matchers.add(matcher);
            log.debug("注册能力匹配策略: {}", matcher.getClass().getSimpleName());
        }
    }

    @Override
    public Set<ToolCapability> allCapabilities() {
        return EnumSet.allOf(ToolCapability.class);
    }

    /**
     * 语义匹配策略。为每种能力类型定义丰富的自然语言描述，通过 Embedding 余弦相似度判定工具归属。
     */
    static class SemanticCapabilityMatcher implements CapabilityMatcher {
        private static final double THRESHOLD = 0.5;

        /** 每种能力类型的语义描述，覆盖典型工具行为场景。 */
        private static final Map<ToolCapability, String> DESCRIPTIONS = Map.of(
                ToolCapability.QUERY,
                "Searching, querying, and retrieving data or information from databases, APIs, search engines, file systems, knowledge bases or any data sources. Read-only lookups, searches, queries, and information fetching operations that access and return existing data without modifying state.",
                ToolCapability.WRITE,
                "Creating, writing, updating, modifying, deleting, or persisting data and resources. State-changing operations that create new records, update existing ones, delete entries, send messages, submit forms, post content, or transmit data to external systems.",
                ToolCapability.CODE_EXECUTION,
                "Executing code, scripts, commands, or programs in programming languages, shell environments, or runtime containers. Running calculations, performing automated tasks, compiling, building, interpreting code, and orchestrating computational workflows.",
                ToolCapability.WEB_SEARCH,
                "Searching the web, accessing online content, making HTTP requests, fetching URLs, retrieving web pages, browsing websites, scraping online data, consuming REST or GraphQL APIs over the internet, and aggregating online information or news.",
                ToolCapability.FILE_OPERATION,
                "Reading, writing, creating, editing, managing, and organizing files and documents on local or remote file systems. File manipulation operations including listing directories, opening files, reading content, saving changes, moving, copying, renaming, deleting files, and managing storage."
        );

        private final IEmbeddingService embeddingService;
        private final Map<ToolCapability, float[]> capabilityEmbeddings;

        SemanticCapabilityMatcher(IEmbeddingService embeddingService) {
            this.embeddingService = embeddingService;
            this.capabilityEmbeddings = computeCapabilityEmbeddings();
        }

        private Map<ToolCapability, float[]> computeCapabilityEmbeddings() {
            ToolCapability[] values = ToolCapability.values();
            List<String> descriptions = Arrays.stream(values)
                    .map(c -> DESCRIPTIONS.getOrDefault(c, c.label()))
                    .toList();
            try {
                List<float[]> vectors = embeddingService.embedBatch(descriptions);
                Map<ToolCapability, float[]> map = new EnumMap<>(ToolCapability.class);
                for (int i = 0; i < values.length && i < vectors.size(); i++) {
                    if (vectors.get(i) != null) {
                        map.put(values[i], vectors.get(i));
                    }
                }
                if (map.size() < values.length) {
                    log.warn("部分能力标签 embedding 计算失败: 成功 {}/{}", map.size(), values.length);
                }
                return map.isEmpty() ? Map.of() : Collections.unmodifiableMap(map);
            } catch (Exception e) {
                log.warn("能力标签 embedding 计算失败，返回空映射: {}", e.getMessage());
                return Map.of();
            }
        }

        /**
         * 批量语义匹配。一次 {@link IEmbeddingService#embedBatch} 完成所有工具的能力向量化，
         * 大幅减少 HTTP 请求数。由 {@link DefaultToolCapabilityRegistry#resolveAll} 调用。
         */
        List<List<String>> matchAll(List<McpSchema.Tool> tools) {
            if (capabilityEmbeddings.isEmpty()) {
                return tools.stream().map(t -> List.<String>of()).toList();
            }
            List<String> descriptions = tools.stream()
                    .map(t -> {
                        String name = t.name() != null ? t.name() : "";
                        String desc = t.description() != null ? t.description() : "";
                        return (name + " " + desc).trim();
                    })
                    .toList();
            try {
                List<float[]> vectors = embeddingService.embedBatch(descriptions);
                List<List<String>> results = new ArrayList<>(tools.size());
                for (int i = 0; i < tools.size(); i++) {
                    float[] vec = i < vectors.size() ? vectors.get(i) : null;
                    if (vec == null) {
                        results.add(List.of());
                        continue;
                    }
                    List<String> caps = new ArrayList<>();
                    for (Map.Entry<ToolCapability, float[]> entry : capabilityEmbeddings.entrySet()) {
                        if (CosineSimilarity.compute(vec, entry.getValue()) >= THRESHOLD) {
                            caps.add(entry.getKey().label());
                        }
                    }
                    results.add(caps);
                }
                return results;
            } catch (Exception e) {
                log.warn("批量工具能力语义匹配失败: {}", e.getMessage());
                return tools.stream().map(t -> this.match(
                        t.name() != null ? t.name() : "",
                        t.description() != null ? t.description() : "")
                ).map(s -> s.stream().map(ToolCapability::label).toList()).toList();
            }
        }

        @Override
        public Set<ToolCapability> match(String toolName, String toolDescription) {
            if (capabilityEmbeddings.isEmpty()) return Set.of();
            String text = (toolName + " " + toolDescription).trim();
            if (text.isBlank()) return Set.of();

            try {
                float[] toolVec = embeddingService.embed(text);
                if (toolVec == null) return Set.of();

                Set<ToolCapability> result = new LinkedHashSet<>();
                for (Map.Entry<ToolCapability, float[]> entry : capabilityEmbeddings.entrySet()) {
                    if (CosineSimilarity.compute(toolVec, entry.getValue()) >= THRESHOLD) {
                        result.add(entry.getKey());
                    }
                }
                return result;
            } catch (Exception e) {
                log.warn("工具能力语义匹配失败: toolName={}, {}", toolName, e.getMessage());
                return Set.of();
            }
        }
    }

    /**
     * 关键词匹配策略（降级方案）。仅当 Embedding 服务不可用时启用。
     */
    static class KeywordCapabilityMatcher implements CapabilityMatcher {
        private static final List<KeywordRule> RULES = List.of(
                new KeywordRule(ToolCapability.QUERY,
                        "search", "query", "get", "list", "find", "fetch"),
                new KeywordRule(ToolCapability.WRITE,
                        "write", "create", "post", "send", "update", "delete"),
                new KeywordRule(ToolCapability.CODE_EXECUTION,
                        "code", "execute", "run", "shell", "command"),
                new KeywordRule(ToolCapability.WEB_SEARCH,
                        "web", "http", "url", "api", "news", "trend"),
                new KeywordRule(ToolCapability.FILE_OPERATION,
                        "file", "read", "document")
        );

        @Override
        public Set<ToolCapability> match(String toolName, String toolDescription) {
            Set<ToolCapability> result = new LinkedHashSet<>();
            String text = (toolName + " " + toolDescription).toLowerCase();
            for (KeywordRule rule : RULES) {
                for (String keyword : rule.keywords) {
                    if (text.contains(keyword)) {
                        result.add(rule.capability);
                        break;
                    }
                }
            }
            return result;
        }

        private record KeywordRule(ToolCapability capability, String... keywords) {}
    }
}
