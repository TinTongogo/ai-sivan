package com.icusu.sivan.agent.tool;

import com.icusu.sivan.agent.mcp.McpConnectionManager;
import com.icusu.sivan.core.tool.ToolSpec;
import com.icusu.sivan.domain.agent.AgentDefinition;
import com.icusu.sivan.domain.agent.IAgentRepository;
import com.icusu.sivan.domain.shared.util.CosineSimilarity;
import com.icusu.sivan.domain.tool.IToolUsageRepository;
import com.icusu.sivan.domain.tool.ToolMeta;
import com.icusu.sivan.domain.tool.ToolRequirement;
import com.icusu.sivan.domain.shared.port.IEmbeddingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 智能体工具自动解析器（默认实现）。
 * <p>
 * 根据智能体画像（systemPrompt + craftDeclaration）和工具需求配置，
 * 从已注册的 MCP 工具中自动匹配最相关的工具集。
 *
 * @see ToolResolver
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultToolResolver implements ToolResolver {

    private final ToolIndex toolIndex;
    private final IAgentRepository agentRepository;
    private final IEmbeddingService embeddingService;
    private final IToolUsageRepository toolUsageRepository;
    private final McpConnectionManager mcpConnectionManager;

    /**
     * 工具描述 embedding 缓存：toolName@serverId → float[]，惰性计算，随工具数量变化自动失效。
     */
    private final Map<String, float[]> toolEmbeddingCache = new ConcurrentHashMap<>();
    private volatile int lastToolCount = 0;

    /**
     * TTL 缓存：accountId → (toolName → 使用次数)，60 秒过期，避免重复 GROUP BY。
     */
    private static final long USAGE_CACHE_TTL_MS = 60_000;
    private final Map<UUID, Map<String, Long>> usageFreqCache = new ConcurrentHashMap<>();
    private final Map<UUID, Long> usageCacheTimestamps = new ConcurrentHashMap<>();

    /**
     * 为 Chat 路径解析可用工具。
     */
    public MatchedTools resolveForChat(String content, UUID accountId) {
        return resolveForChat(content, null, accountId);
    }

    /**
     * 为 Chat 路径解析可用工具（带对话上下文感知）。
     * 注意：MCP 连接由调用方（ConversationService）按对话选择的服务器管理，
     * 此处不做 connectAll，避免绕过用户的工具选择。
     *
     * @param conversationContext 最近对话历史文本，用于语义匹配
     */
    public MatchedTools resolveForChat(String content, String conversationContext, UUID accountId) {
        List<ToolMeta> allTools = toolIndex.getAllTools();
        if (allTools.isEmpty()) {
            return MatchedTools.empty();
        }
        // 拼接对话上下文 + 当前消息作为语义匹配上下文
        String semanticContext = content;
        if (conversationContext != null && !conversationContext.isBlank()) {
            semanticContext = conversationContext + "\n" + content;
        }
        // 限制长度避免 embedding 模型输入超限
        if (semanticContext.length() > 2000) {
            semanticContext = semanticContext.substring(semanticContext.length() - 2000);
        }
        ToolRequirement req = ToolRequirement.builder().autoMatch(true).minConfidence(0.5).build();
        return match(allTools, getSchemaMap(), req, semanticContext, accountId);
    }

    /**
     * 为指定的智能体解析工具：根据智能体画像做语义匹配。
     * <p>
     * 如果智能体的 {@link ToolRequirement#getPreferredServers()} 指定了优先服务器，
     * 则先按需连接这些服务器（仅第一次需要），再从 ToolIndex 获取工具列表进行匹配。
     */
    public MatchedTools resolveForAgent(String agentName, UUID accountId) {
        Optional<AgentDefinition> agentOpt = agentRepository.findByAccountAndName(accountId, agentName);
        if (agentOpt.isEmpty()) {
            log.warn("智能体不存在，尝试使用全部工具: agentName={}", agentName);
            List<ToolMeta> allTools = toolIndex.getAllTools();
            return allTools.isEmpty() ? MatchedTools.empty()
                    : match(allTools, getSchemaMap(), null, null, accountId);
        }

        ToolRequirement req = agentOpt.get().getToolRequirements();
        if (req == null) {
            req = ToolRequirement.builder().autoMatch(true).build();
        }

        // 按需连接优先服务器：指定的服务器此时才建立连接
        List<String> preferredServers = req.getPreferredServers();
        if (preferredServers != null && !preferredServers.isEmpty()) {
            for (String serverId : preferredServers) {
                try {
                    mcpConnectionManager.connectByServerId(UUID.fromString(serverId));
                } catch (Exception e) {
                    log.warn("按需连接 MCP 服务器失败 [{}]: {}", serverId, e.getMessage());
                }
            }
        } else if ((req.getRequiredCapabilities() != null && !req.getRequiredCapabilities().isEmpty())
                || req.isAutoMatch()) {
            // 未指定优先服务器，但智能体需要工具匹配：连接所有已启用的服务器进行工具发现
            if (toolIndex.getAllTools().isEmpty()) {
                log.info("智能体需要工具匹配但未指定服务器，连接所有已启用的 MCP 服务器进行发现: agentName={}", agentName);
                try {
                    mcpConnectionManager.connectAll();
                } catch (Exception e) {
                    log.warn("MCP 工具发现阶段连接失败: {}", e.getMessage());
                }
            }
        }

        List<ToolMeta> allTools = toolIndex.getAllTools();
        if (allTools.isEmpty()) {
            return MatchedTools.empty();
        }

        String semanticContext = req.isAutoMatch() ? buildAgentProfile(agentOpt.get()) : null;
        return match(allTools, getSchemaMap(), req, semanticContext, accountId);
    }

    /**
     * 为未指定智能体的场景自动决议工具。
     */
    public MatchedTools resolve(UUID accountId) {
        // 连接所有已启用的 MCP 服务器进行工具发现（与 resolveForAgent 一致）
        if (toolIndex.getAllTools().isEmpty()) {
            try {
                mcpConnectionManager.connectAll();
            } catch (Exception e) {
                log.warn("MCP 自动发现连接失败: {}", e.getMessage());
            }
        }
        List<ToolMeta> allTools = toolIndex.getAllTools();
        if (allTools.isEmpty()) {
            return MatchedTools.empty();
        }
        return match(allTools, getSchemaMap(), null, null, accountId);
    }

    // ===== 内部匹配逻辑 =====

    private MatchedTools match(List<ToolMeta> allTools, Map<String, ToolSpec> schemaMap,
                               ToolRequirement req) {
        return match(allTools, schemaMap, req, null, null);
    }

    private MatchedTools match(List<ToolMeta> allTools, Map<String, ToolSpec> schemaMap,
                               ToolRequirement req, String semanticContext, UUID accountId) {
        if (allTools.isEmpty()) {
            return MatchedTools.empty();
        }

        // 阶段 1: 服务器白名单过滤
        List<ToolMeta> filtered = allTools;
        if (req != null && req.getPreferredServers() != null && !req.getPreferredServers().isEmpty()) {
            filtered = allTools.stream()
                    .filter(t -> req.getPreferredServers().contains(t.getServerId())
                            || req.getPreferredServers().contains(t.getServerName()))
                    .toList();
        }

        // 阶段 2: capability 匹配
        if (req != null && req.getRequiredCapabilities() != null && !req.getRequiredCapabilities().isEmpty()) {
            filtered = filtered.stream()
                    .filter(t -> !Collections.disjoint(t.getCapabilities(), req.getRequiredCapabilities()))
                    .toList();
        }

        // 阶段 3: Embedding 语义匹配
        Map<String, Double> matchScores = Map.of();
        if (req != null && req.isAutoMatch() && semanticContext != null && !semanticContext.isBlank()) {
            SemanticMatchResult sr = semanticMatch(filtered, semanticContext, req.getMinConfidence());
            matchScores = sr.scores();
            if (!sr.matched().isEmpty()) {
                filtered = sr.matched();
            } // 为空则保留阶段 2 的结果，不降级到全部工具
        }

        // 如果所有阶段过滤后无结果，回退到全部
        if (filtered.isEmpty()) {
            filtered = allTools;
        }

        // 阶段 4: 按使用频率排序
        if (accountId != null) {
            filtered = sortByUsage(filtered, accountId);
        }

        List<ToolMeta> matchedMetas = filtered;
        List<ToolSpec> matchedSchemas = matchedMetas.stream()
                .map(m -> schemaMap.get(m.getToolName()))
                .filter(Objects::nonNull)
                .toList();

        log.info("工具匹配结果: {} 个工具匹配 (共 {} 个)", matchedSchemas.size(), allTools.size());
        double threshold = req != null ? req.getMinConfidence() : 0;
        // 构建全部候选工具的 toolName → serverId 映射，供调用方记录匹配日志时使用
        Map<String, String> toolServerIds = allTools.stream()
                .filter(t -> t.getToolName() != null)
                .collect(Collectors.toMap(ToolMeta::getToolName,
                        t -> t.getServerId() != null ? t.getServerId() : "",
                        (a, b) -> a));
        return new MatchedTools(matchedMetas, matchedSchemas, matchScores, threshold, toolServerIds);
    }

    /**
     * 构建智能体能力画像，用于语义匹配。
     */
    private String buildAgentProfile(AgentDefinition agent) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("Agent: ").append(agent.getAgentName() != null ? agent.getAgentName() : "").append("\n");
        sb.append("Description: ").append(agent.getDescription() != null ? agent.getDescription() : "").append("\n");
        sb.append("System Prompt: ").append(agent.getSystemPrompt() != null ? agent.getSystemPrompt() : "").append("\n");
        sb.append("Craft Declaration: ").append(agent.getCraftDeclaration() != null ? agent.getCraftDeclaration() : "").append("\n");
        return sb.toString();
    }

    /**
     * 语义匹配：对上下文做 embedding，与工具描述 embedding 比较余弦相似度。
     *
     * @return 匹配结果，包含过滤后的工具列表和全部工具的相似度分数
     */
    private SemanticMatchResult semanticMatch(List<ToolMeta> tools, String context, double minConfidence) {
        try {
            float[] contextVec = embeddingService.embed(context);
            Map<String, float[]> toolVecs = getToolEmbeddings(tools);

            Map<String, Double> scores = new java.util.LinkedHashMap<>();
            List<ToolMeta> matched = new java.util.ArrayList<>();
            for (ToolMeta t : tools) {
                float[] tv = toolVecs.get(cacheKey(t));
                double sim = tv != null ? CosineSimilarity.compute(contextVec, tv) : 0;
                scores.put(t.getToolName(), sim);
                if (sim >= minConfidence) {
                    matched.add(t);
                }
            }
            if (matched.isEmpty() && !tools.isEmpty()) {
                double maxSim = scores.values().stream().mapToDouble(Double::doubleValue).max().orElse(0);
                log.warn("语义匹配结果为空: {} 个工具均低于阈值 {}, 最高相似度={}, 上下文长度={}",
                        tools.size(), minConfidence, String.format("%.3f", maxSim), context.length());
            }
            return new SemanticMatchResult(matched, scores);
        } catch (Exception e) {
            log.warn("语义匹配失败，跳过 Stage 3: {}", e.getMessage());
            return new SemanticMatchResult(tools, Map.of());
        }
    }

    private record SemanticMatchResult(List<ToolMeta> matched, Map<String, Double> scores) {}

    /**

    /**
     * 获取工具描述 embedding（惰性计算 + 缓存）。对未缓存的工具描述做单次批量 embedding，
     * 避免逐调用 {@code embeddingService.embed()} 产生大量 HTTP 请求。
     */
    private Map<String, float[]> getToolEmbeddings(List<ToolMeta> tools) {
        int currentCount = toolIndex.getAllTools().size();
        if (currentCount != lastToolCount) {
            toolEmbeddingCache.clear();
            lastToolCount = currentCount;
        }
        // 1. 分离已缓存和未缓存的工具
        Map<String, float[]> result = new java.util.HashMap<>();
        java.util.List<ToolMeta> uncached = new java.util.ArrayList<>();
        for (ToolMeta tool : tools) {
            String key = cacheKey(tool);
            float[] cached = toolEmbeddingCache.get(key);
            if (cached != null) {
                result.put(key, cached);
            } else {
                uncached.add(tool);
            }
        }
        // 2. 批量 embedding 未缓存的工具描述
        if (!uncached.isEmpty()) {
            List<String> descriptions = uncached.stream()
                    .map(t -> t.getDescription() != null ? t.getDescription() : "")
                    .toList();
            List<float[]> batchResult;
            try {
                batchResult = embeddingService.embedBatch(descriptions);
            } catch (Exception e) {
                log.warn("批量工具描述 embedding 失败: {}", e.getMessage());
                batchResult = List.of();
            }
            for (int i = 0; i < uncached.size() && i < batchResult.size(); i++) {
                String key = cacheKey(uncached.get(i));
                float[] vec = batchResult.get(i);
                if (vec != null) {
                    toolEmbeddingCache.put(key, vec);
                    result.put(key, vec);
                }
            }
        }
        return result;
    }

    private static String cacheKey(ToolMeta tool) {
        return tool.getToolName() + "@" + tool.getServerId();
    }

    /**
     * 从 ToolIndex 构建 name→ToolSpec 的查找表。
     */
    private Map<String, ToolSpec> getSchemaMap() {
        List<ToolMeta> allTools = toolIndex.getAllTools();
        return allTools.stream()
                .filter(t -> t.getInputSchema() != null)
                .collect(Collectors.toMap(
                        ToolMeta::getToolName,
                        t -> new ToolSpec(t.getToolName(), t.getDescription(), t.getInputSchema()),
                        (a, b) -> a));
    }

    /**
     * 按历史使用频率降序排列工具（最常使用优先）。v6: 带 60s TTL 缓存避免重复 GROUP BY。
     */
    private List<ToolMeta> sortByUsage(List<ToolMeta> tools, UUID accountId) {
        try {
            Map<String, Long> freqMap = getCachedUsageFreq(accountId);
            if (freqMap == null || freqMap.isEmpty()) {
                return tools;
            }
            return tools.stream()
                    .sorted(Comparator.<ToolMeta, Long>comparing(
                            t -> freqMap.getOrDefault(t.getToolName(), 0L),
                            Comparator.reverseOrder()
                    ))
                    .toList();
        } catch (Exception e) {
            log.warn("工具使用频率排序失败，使用原始顺序: {}", e.getMessage());
            return tools;
        }
    }

    /**
     * 获取缓存的使用频率映射，TTL 60s。缓存未命中或过期时查询数据库。
     */
    private Map<String, Long> getCachedUsageFreq(UUID accountId) {
        long now = System.currentTimeMillis();
        Long lastUpdate = usageCacheTimestamps.get(accountId);
        if (lastUpdate != null && (now - lastUpdate) < USAGE_CACHE_TTL_MS) {
            Map<String, Long> cached = usageFreqCache.get(accountId);
            if (cached != null) return cached;
        }

        List<Object[]> usageData = toolUsageRepository.countByToolName(accountId);
        Map<String, Long> freqMap = usageData == null || usageData.isEmpty()
                ? Collections.emptyMap()
                : usageData.stream()
                .collect(Collectors.toMap(
                        row -> (String) row[0],
                        row -> ((Number) row[1]).longValue()
                ));

        usageFreqCache.put(accountId, freqMap);
        usageCacheTimestamps.put(accountId, now);
        return freqMap;
    }
}
