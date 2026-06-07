package com.icusu.sivan.orch.topology;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.icusu.sivan.agent.prompt.SquadPrompts;
import com.icusu.sivan.common.enums.SquadMode;
import com.icusu.sivan.core.message.Msg;
import com.icusu.sivan.core.message.Role;
import com.icusu.sivan.agent.model.ModelRouter;
import com.icusu.sivan.core.model.Model.ModelParams;
import com.icusu.sivan.domain.agent.AgentDefinition;
import com.icusu.sivan.domain.agent.IAgentRepository;
import com.icusu.sivan.domain.feedback.IPatternFeedbackRepository;
import com.icusu.sivan.domain.feedback.PatternFeedbackRecord;
import com.icusu.sivan.domain.feedback.FeatureDeviation;
import com.icusu.sivan.domain.memory.InstinctPattern;
import com.icusu.sivan.domain.orchestration.PhaseNode;
import com.icusu.sivan.domain.shared.util.CosineSimilarity;
import com.icusu.sivan.infra.knowledge.EmbeddingService;
import com.icusu.sivan.memory.instinct.InstinctPatternService;
import com.icusu.sivan.memory.pattern.FeatureExtractor;
import reactor.core.publisher.Mono;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Squad 拓扑生成器。
 * 根据任务描述动态生成 Squad 执行阶段拓扑，
 * 优先匹配本能模板，无匹配时通过 LLM 生成。
 *
 * <p>v2 增强：生成拓扑前先做能力缺口分析（CapabilityGap），
 * 将已有智能体按任务匹配度排序后注入 LLM prompt，
 * 引导 LLM 优先复用而非创建新的。</p>
 */
@Slf4j
@Component
public class TopologyGenerator {

    /**
     * LLM 推断的 Squad 元信息。
     */
    public record SquadMeta(String name, String description, String taskType) {
    }

    /**
     * 能力缺口分析结果。
     *
     * @param matchedAgents    匹配度 ≥ 阈值、建议直接复用的智能体
     * @param formattedContext 格式化后的 agent 列表（含匹配度），注入 LLM prompt
     */
    public record CapabilityGap(List<AgentDefinition> matchedAgents, String formattedContext) {

        public static CapabilityGap empty() {
            return new CapabilityGap(List.of(), "");
        }

        public boolean hasContext() {
            return formattedContext != null && !formattedContext.isBlank();
        }
    }

    private record AgentMatch(AgentDefinition agent, double similarity) {
    }

    /** 能力匹配阈值：cosine similarity ≥ 此值视为高匹配。 */
    private static final double CAPABILITY_THRESHOLD = 0.65;

    private final InstinctPatternService instinctPatternService;
    private final ModelRouter modelRouter;
    private final ObjectMapper objectMapper;
    private final IAgentRepository agentRepository;
    private final EmbeddingService embeddingService;
    private final IPatternFeedbackRepository patternFeedbackRepository;
    private final FeatureExtractor featureExtractor;

    public TopologyGenerator(InstinctPatternService instinctPatternService,
                             ModelRouter modelRouter,
                             ObjectMapper objectMapper,
                             IAgentRepository agentRepository,
                             EmbeddingService embeddingService,
                             IPatternFeedbackRepository patternFeedbackRepository,
                             FeatureExtractor featureExtractor) {
        this.instinctPatternService = instinctPatternService;
        this.modelRouter = modelRouter;
        this.objectMapper = objectMapper;
        this.agentRepository = agentRepository;
        this.embeddingService = embeddingService;
        this.patternFeedbackRepository = patternFeedbackRepository;
        this.featureExtractor = featureExtractor;
    }

    /**
     * 生成 Squad 执行拓扑（含本能模板匹配 + 已有 agent 列表）。
     */
    public Mono<TopologyResult> generate(UUID accountId, String taskDescription) {
        return generateWithAgentContext(accountId, taskDescription);
    }

    /**
     * 为新建 Squad 生成拓扑。
     * 自动执行能力缺口分析，将已有智能体按匹配度排序后注入 LLM prompt。
     */
    public Mono<TopologyResult> generateForNewSquad(UUID accountId, String taskDescription) {
        return generateWithAgentContext(accountId, taskDescription);
    }

    /**
     * 为新建 Squad 生成拓扑，使用外部提供的 agent context。
     * 被 {@link SquadCreator} 调用，传入能力缺口分析结果。
     */
    public Mono<TopologyResult> generateForNewSquad(UUID accountId, String taskDescription, CapabilityGap gap) {
        return Mono.defer(() -> {
            // 1. 优先本能模板匹配
            Optional<InstinctPattern> matched = instinctPatternService.match(featureExtractor.extractHeuristic(taskDescription), accountId);
            if (matched.isPresent()) {
                InstinctPattern pattern = matched.get();
                List<PhaseNode> phases = parseTopologyJson(pattern.getTopologyJson());
                if (!phases.isEmpty()) {
                    log.info("拓扑生成命中本能模板: patternId={}, phases={}", pattern.getPatternId(), phases.size());
                    return Mono.just(toTopologyResult(phases, pattern.getTopologyJson(), pattern.getPatternId()));
                }
            }

            // 2. 无模板 → LLM 生成（注入能力缺口数据）
            if (gap.hasContext()) {
                return generate(accountId, taskDescription, false, gap.formattedContext(), null);
            }
            // 无缺口数据时自分析
            return generateForNewSquad(accountId, taskDescription);
        });
    }

    // ========== 能力缺口分析 ==========

    /**
     * 分析任务描述与已有智能体的能力匹配情况。
     * 对 taskDescription 做 embedding，与每个已有 agent 的画像计算 cosine similarity。
     *
     * @return CapabilityGap — matchedAgents 为高匹配 agent；formattedContext 为注入 prompt 的文本
     */
    public CapabilityGap analyzeCapabilityGap(UUID accountId, String taskDescription) {
        List<AgentDefinition> agents = getAvailableAgents(accountId);
        if (agents.isEmpty()) {
            return CapabilityGap.empty();
        }

        try {
            float[] taskVec = embeddingService.embed(taskDescription);

            // 批量 embedding 所有 agent 画像，避免逐个 HTTP 请求
            List<String> profiles = agents.stream()
                    .map(a -> a.getAgentName()
                            + " " + (a.getDescription() != null ? a.getDescription() : "")
                            + " " + (a.getCraftDeclaration() != null ? a.getCraftDeclaration() : ""))
                    .toList();
            List<float[]> agentVecs = embeddingService.embedBatch(profiles);

            List<AgentMatch> matches = new ArrayList<>();
            for (int i = 0; i < agents.size() && i < agentVecs.size(); i++) {
                float[] agentVec = agentVecs.get(i);
                if (agentVec != null) {
                    matches.add(new AgentMatch(agents.get(i), CosineSimilarity.compute(taskVec, agentVec)));
                }
            }
            matches.sort(Comparator.comparingDouble(AgentMatch::similarity).reversed());

            List<AgentDefinition> matched = matches.stream()
                    .filter(m -> m.similarity() >= CAPABILITY_THRESHOLD)
                    .map(AgentMatch::agent)
                    .toList();

            String context = formatAgentList(matches);
            return new CapabilityGap(matched, context);
        } catch (Exception e) {
            log.warn("能力缺口分析失败，回退到普通 agent 列表: {}", e.getMessage());
            String fallback = formatAgentListFallback(agents);
            return new CapabilityGap(List.of(), fallback);
        }
    }

    /**
     * 格式化 agent 列表（含匹配度 + 推荐标记），供 LLM prompt 使用。
     */
    private static String formatAgentList(List<AgentMatch> matches) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("已有智能体（按任务匹配度从高到低排列，匹配度 ≥ 0.65 为高匹配，必须优先复用）：\n");
        for (int i = 0; i < matches.size(); i++) {
            AgentMatch m = matches.get(i);
            AgentDefinition a = m.agent();
            String indicator = m.similarity() >= CAPABILITY_THRESHOLD ? " ★ ★ ★ 强烈建议复用" : "";
            sb.append(i + 1).append(". ").append(a.getAgentName());
            if (a.getCategory() != null) sb.append(" [").append(a.getCategory()).append("]");
            sb.append(": ").append(a.getDescription() != null ? a.getDescription() : "无描述");
            if (a.getCraftDeclaration() != null) {
                sb.append(" | 专长: ").append(a.getCraftDeclaration());
            }
            sb.append(" | 匹配度: ").append(String.format("%.2f", m.similarity()));
            sb.append(indicator).append("\n");
        }
        sb.append("\n请优先复用高匹配智能体，只有在能力完全不匹配时才创建新角色。");
        return sb.toString();
    }

    /**
     * 无 embedding 时的回退格式。
     */
    private static String formatAgentListFallback(List<AgentDefinition> agents) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("已有以下智能体（Agent）可用（请优先复用，不存在的角色可以提出新名称）：\n");
        for (AgentDefinition a : agents) {
            sb.append("- ").append(a.getAgentName());
            if (a.getCategory() != null) sb.append(" [").append(a.getCategory()).append("]");
            sb.append(": ").append(a.getDescription() != null ? a.getDescription() : "无描述");
            sb.append("\n");
        }
        return sb.toString();
    }

    // ========== 内部生成方法 ==========

    /**
     * 生成拓扑：先尝试本能模板匹配，失败后通过 LLM 生成。
     * agentContext 为空时等同于「无已有 agent」模式。
     */
    private Mono<TopologyResult> generateWithAgentContext(UUID accountId, String taskDescription) {
        // 1. 本能模板匹配
        Optional<InstinctPattern> matched = instinctPatternService.match(featureExtractor.extractHeuristic(taskDescription), accountId);
        if (matched.isPresent()) {
            InstinctPattern pattern = matched.get();
            List<PhaseNode> phases = parseTopologyJson(pattern.getTopologyJson());
            if (!phases.isEmpty()) {
                log.info("拓扑生成命中本能模板: patternId={}, phases={}",
                        pattern.getPatternId(), phases.size());
                return Mono.just(toTopologyResult(phases, pattern.getTopologyJson(), pattern.getPatternId()));
            }
        }

        // 2. 能力缺口分析
        CapabilityGap gap = analyzeCapabilityGap(accountId, taskDescription);

        // 3. LLM 生成
        return generate(accountId, taskDescription, false, gap.formattedContext(), null);
    }

    /**
     * 核心 LLM 生成方法。
     *
     * @param agentContext      格式化后的 agent 列表文本，空字符串 = 不注入 agent 信息
     * @param matchedPattern   可选，已匹配的模板（探索模式时传入，用于 LLM 生成后对比）
     */
    private Mono<TopologyResult> generate(UUID accountId, String taskDescription,
                                           boolean tryPattern, String agentContext,
                                           InstinctPattern matchedPattern) {
        // 1. 尝试本能模板（仅在无外部匹配模板时）
        if (tryPattern && matchedPattern == null) {
            Optional<InstinctPattern> patternOpt = instinctPatternService.match(featureExtractor.extractHeuristic(taskDescription), accountId);
            if (patternOpt.isPresent()) {
                InstinctPattern pattern = patternOpt.get();
                List<PhaseNode> phases = parseTopologyJson(pattern.getTopologyJson());
                if (!phases.isEmpty()) {
                    log.info("拓扑生成命中本能模板: patternId={}, phases={}",
                            pattern.getPatternId(), phases.size());
                    return Mono.just(toTopologyResult(phases, pattern.getTopologyJson(), pattern.getPatternId()));
                }
            }
        }

        // 2. 构造 prompt（O4：纯静态 system + 含 agents 的 DYNAMIC user，支持前缀缓存）
        String systemPrompt = SquadPrompts.TOPOLOGY_GENERATE_SYSTEM_STATIC.content();
        String userPrompt = SquadPrompts.topologyGenerateUser(taskDescription, agentContext).content();

        // 3. LLM 生成拓扑
        InstinctPattern finalPattern = matchedPattern;
        return llmChat(systemPrompt, userPrompt, accountId)
                .map(llmResponse -> {
                    List<PhaseNode> phases = parseLlmResponse(llmResponse);

                    if (phases.isEmpty()) {
                        log.warn("LLM 拓扑解析为空，原始响应（前500字符）: {}",
                                llmResponse != null ? llmResponse.substring(0, Math.min(llmResponse.length(), 500)) : "null");
                        // 有已有 agent 时回退到单阶段拓扑
                        List<AgentDefinition> agents = getAvailableAgents(accountId);
                        if (!agents.isEmpty()) {
                            phases = List.of(PhaseNode.builder()
                                    .phase(0)
                                    .name("任务执行")
                                    .agents(agents.stream().map(AgentDefinition::getAgentName).toList())
                                    .description(taskDescription)
                                    .mode(SquadMode.SEQUENTIAL)
                                    .build());
                        }
                        // 无已有 agent 时，生成通用单阶段拓扑
                        if (phases.isEmpty()) {
                            phases = List.of(PhaseNode.builder()
                                    .phase(0)
                                    .name("任务执行")
                                    .agents(List.of("执行者"))
                                    .description(taskDescription)
                                    .mode(SquadMode.SEQUENTIAL)
                                    .build());
                        }
                    }

                    // DAG 验证：不通过则降级 SEQUENTIAL
                    try {
                        DagValidator.validate(phases);
                    } catch (DagValidator.DagValidationException e) {
                        log.warn("LLM 拓扑 DAG 验证失败，降级 SEQUENTIAL: {}", e.getMessage());
                        phases = List.of(PhaseNode.builder()
                                .phase(0).name("任务执行")
                                .agents(List.of("执行者"))
                                .description(taskDescription)
                                .mode(SquadMode.SEQUENTIAL)
                                .build());
                    }

                    // 4. 探索模式：LLM 结果与模板拓扑对比并记录反馈
                    if (finalPattern != null) {
                        compareAndRecordFeedback(accountId, taskDescription, finalPattern, phases);
                    }

                    log.info("拓扑生成完成（LLM）: phases={}", phases.size());
                    return TopologyResult.builder()
                            .mode(detectOverallMode(phases))
                            .phases(phases)
                            .fromPattern(false)
                            .build();
                });
    }

    // ========== 推断 Squad 元信息 ==========

    /**
     * 从任务描述推断 Squad 元信息（名称、描述、任务类型）。
     */
    public Mono<SquadMeta> inferSquadMeta(UUID accountId, String taskDescription) {
        return inferSquadMeta(accountId, taskDescription, null);
    }

    /**
     * 从任务描述推断 Squad 元信息（名称、描述、任务类型），带上下文感知。
     *
     * @param historyContext 对话历史上下文（用于"继续处理"等简短指令的语义增强）
     */
    public Mono<SquadMeta> inferSquadMeta(UUID accountId, String taskDescription, String historyContext) {
        String prompt = SquadPrompts.squadNamingUser(taskDescription, historyContext).content();

        return llmChat(SquadPrompts.SQUAD_NAMING_SYSTEM.content(), prompt, accountId)
                .map(llmResponse -> {
                    String name = "自动团队";
                    String description = taskDescription.length() > 30 ? taskDescription.substring(0, 30) : taskDescription;
                    String taskType = "auto_task";

                    try {
                        String json = extractJson(llmResponse);
                        var root = objectMapper.readTree(json);
                        if (root.has("squadName")) name = sanitizeName(root.get("squadName").asText(), 64);
                        if (root.has("squadDescription")) description = truncate(root.get("squadDescription").asText(), 200);
                        if (root.has("taskType")) taskType = sanitizeName(root.get("taskType").asText(), 48);
                    } catch (Exception e) {
                        log.warn("推断 Squad 元信息失败，使用默认值: {}", e.getMessage());
                    }

                    return new SquadMeta(name, description, taskType);
                });
    }

    // ========== LLM 解析 ==========

    /**
     * 将 LLM 输出的模式字符串安全解析为 SquadMode。
     */
    private static SquadMode parseMode(String modeStr) {
        if (modeStr == null || modeStr.isBlank()) return SquadMode.SEQUENTIAL;
        try {
            return SquadMode.valueOf(modeStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("未知模式字符串 {}，回退 SEQUENTIAL", modeStr);
            return SquadMode.SEQUENTIAL;
        }
    }

    /** 创建 TopologyResult，附带 DAG 验证。验证失败时以 SEQUENTIAL 降级。 */
    private TopologyResult toTopologyResult(List<PhaseNode> phases, String topologyJson, UUID patternId) {
        try {
            DagValidator.validate(phases);
        } catch (DagValidator.DagValidationException e) {
            log.warn("本能模板 DAG 验证失败，降级 SEQUENTIAL: {}", e.getMessage());
            phases = phases.stream()
                    .map(p -> PhaseNode.builder()
                            .phase(p.getPhase()).name(p.getName())
                            .agents(p.getAgents()).description(p.getDescription())
                            .mode(SquadMode.SEQUENTIAL)
                            .build())
                    .toList();
        }
        return TopologyResult.builder()
                .mode(detectOverallMode(phases))
                .phases(phases)
                .fromPattern(true)
                .patternId(patternId)
                .build();
    }

    /**
     * 检测阶段数组的整体模式：如果所有阶段模式一样则返回该模式，否则返回 SEQUENTIAL。
     */
    private static SquadMode detectOverallMode(List<PhaseNode> phases) {
        if (phases.isEmpty()) return SquadMode.SEQUENTIAL;
        SquadMode first = phases.getFirst().getMode();
        if (first == null) return SquadMode.SEQUENTIAL;
        for (PhaseNode p : phases) {
            if (first != p.getMode()) return SquadMode.SEQUENTIAL;
        }
        return first;
    }

    /**
     * 解析 LLM 返回的 JSON 阶段列表。
     */
    private List<PhaseNode> parseLlmResponse(String response) {
        if (response == null || response.isBlank()) return List.of();

        String json = response.trim();

        // 剥离 LLM 常见闲聊前缀
        int bracketStart = json.indexOf('[');
        int braceStart = json.indexOf('{');
        int firstJson = bracketStart >= 0 && braceStart >= 0 ? Math.min(bracketStart, braceStart)
                : bracketStart >= 0 ? bracketStart : braceStart;
        if (firstJson > 0) {
            json = json.substring(firstJson);
        }

        // 剥离 markdown 代码块
        if (json.startsWith("```")) {
            int fenceEnd = json.indexOf('\n');
            if (fenceEnd > 0) json = json.substring(fenceEnd + 1);
            if (json.endsWith("```")) json = json.substring(0, json.length() - 3).trim();
        }

        // 处理 wrapper 对象 {"phases": [...]}
        if (json.startsWith("{") && json.contains("\"phases\"")) {
            try {
                var root = objectMapper.readTree(json);
                if (root.has("phases")) {
                    return parsePhaseArray((ArrayNode) root.get("phases"));
                }
            } catch (Exception ignored) { /* fall through */ }
        }

        // 提取 JSON 数组
        int start = json.indexOf('[');
        int end = json.lastIndexOf(']');
        if (start < 0 || end < 0) return List.of();

        try {
            return parsePhaseArray((ArrayNode) objectMapper.readTree(json.substring(start, end + 1)));
        } catch (Exception e) {
            log.warn("LLM 返回格式无法解析: {}", e.getMessage());
            return List.of();
        }
    }

    private List<PhaseNode> parsePhaseArray(ArrayNode array) {
        List<PhaseNode> phases = new ArrayList<>();
        for (JsonNode node : array) {
            PhaseNode phase = PhaseNode.builder()
                    .phase(node.has("phase") ? node.get("phase").asInt() : phases.size())
                    .name(node.has("name") ? node.get("name").asText() : "阶段" + phases.size())
                    .agents(node.has("agents") ? readStringList(node.get("agents")) : List.of())
                    .description(node.has("description") ? node.get("description").asText() : null)
                    .mode(node.has("mode") ? parseMode(node.get("mode").asText()) : SquadMode.SEQUENTIAL)
                    .dependsOn(node.has("dependsOn") ? readIntList(node.get("dependsOn")) : null)
                    .build();
            phases.add(phase);
        }
        return phases;
    }

    private List<PhaseNode> parseTopologyJson(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            JsonNode root = objectMapper.readTree(json);
            if (root.isArray()) {
                return parsePhaseArray((ArrayNode) root);
            }
            if (root.has("phases")) {
                return parsePhaseArray((ArrayNode) root.get("phases"));
            }
        } catch (Exception e) {
            log.warn("模板 JSON 解析失败: {}", e.getMessage());
        }
        return List.of();
    }

    private List<String> readStringList(JsonNode node) {
        List<String> list = new ArrayList<>();
        if (node.isArray()) {
            node.forEach(n -> list.add(n.asText()));
        }
        return list;
    }

    private List<Integer> readIntList(JsonNode node) {
        List<Integer> list = new ArrayList<>();
        if (node.isArray()) {
            node.forEach(n -> list.add(n.asInt()));
        }
        return list;
    }

    // ========== 工具 ==========

    private List<AgentDefinition> getAvailableAgents(UUID accountId) {
        return agentRepository.findAllByAccount(accountId);
    }

    private static String extractJson(String text) {
        if (text == null || text.isBlank()) return "{}";
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end < 0) return "{}";
        return text.substring(start, end + 1);
    }

    /** 调用 LLM 并返回文本回复（响应式）。 */
    private Mono<String> llmChat(String systemPrompt, String userPrompt, UUID accountId) {
        return modelRouter.getDefaultModel(accountId).chat(
                List.of(
                        Msg.of(Role.SYSTEM, systemPrompt),
                        Msg.of(Role.USER, userPrompt)
                ),
                ModelParams.defaults()
        ).map(response -> response != null ? response.msg().text() : "")
                .onErrorReturn(e -> {
                    log.warn("LLM 调用失败: {}", e.getMessage());
                    return true;
                }, "");
    }

    private static final java.util.regex.Pattern SAFE_NAME = java.util.regex.Pattern.compile("[\\w\\u4e00-\\u9fff\\-_. ]+");

    private static String sanitizeName(String name, int maxLen) {
        if (name == null || name.isBlank()) return name;
        String cleaned = name.length() > maxLen ? name.substring(0, maxLen) : name;
        return SAFE_NAME.matcher(cleaned).matches() ? cleaned : cleaned.replaceAll("[^\\w\\u4e00-\\u9fff\\-_. ]", "");
    }

    /**
     * 对比 LLM 生成拓扑与模板拓扑，偏差较小（similarity ≥ 0.7）时写入反馈。
     */
    private void compareAndRecordFeedback(UUID accountId, String taskDescription,
                                           InstinctPattern pattern, List<PhaseNode> llmPhases) {
        try {
            List<PhaseNode> templatePhases = parseTopologyJson(pattern.getTopologyJson());
            if (templatePhases.isEmpty()) return;

            double similarity = StructuralSimilarity.compute(templatePhases, llmPhases);
            double threshold = 0.7;
            if (similarity >= threshold) {
                List<String> mismatchDims = new java.util.ArrayList<>();
                if (templatePhases.size() != llmPhases.size()) {
                    mismatchDims.add("phase_count");
                }

                PatternFeedbackRecord record = PatternFeedbackRecord.builder()
                        .patternId(pattern.getPatternId())
                        .accountId(accountId)
                        .taskDescription(taskDescription)
                        .outcome(PatternFeedbackRecord.FeedbackOutcome.SUCCESS)
                        .deviation(new FeatureDeviation(true, similarity, 1.0, mismatchDims,
                                "LLM 拓扑与模板拓扑偏差较小 (similarity="
                                        + String.format("%.2f", similarity) + ")"))
                        .source(PatternFeedbackRecord.FeedbackSource.TRIGGER_LLM.name())
                        .build();
                patternFeedbackRepository.save(record);
                log.info("探索模式拓扑对比: patternId={}, similarity={}, 已记录反馈",
                        pattern.getPatternId(), String.format("%.2f", similarity));
            } else {
                log.debug("探索模式拓扑对比: patternId={}, similarity={}, 低于阈值 0.7",
                        pattern.getPatternId(), String.format("%.2f", similarity));
            }
        } catch (Exception e) {
            log.warn("拓扑对比反馈记录失败: patternId={}", pattern.getPatternId(), e);
        }
    }

    private static String truncate(String text, int maxLen) {
        if (text == null || text.isBlank()) return text;
        return text.length() > maxLen ? text.substring(0, maxLen) : text;
    }
}
