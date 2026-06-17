package com.icusu.sivan.agent.routing.engine;

import com.icusu.sivan.agent.model.DefaultModelRouter;
import com.icusu.sivan.agent.prompt.AgentPrompts;
import com.icusu.sivan.agent.prompt.IntentClassifier;
import com.icusu.sivan.agent.prompt.PromptUtils;
import com.icusu.sivan.agent.prompt.SkillPrompts;
import com.icusu.sivan.common.enums.SkillStatus;
import com.icusu.sivan.common.enums.SkillType;
import com.icusu.sivan.core.message.Content;
import com.icusu.sivan.core.message.Msg;
import com.icusu.sivan.core.message.Role;
import com.icusu.sivan.core.model.Model;
import com.icusu.sivan.domain.agent.AgentDefinition;
import com.icusu.sivan.domain.agent.IAgentRepository;
import com.icusu.sivan.domain.agent.ISkillRepository;
import com.icusu.sivan.domain.agent.Skill;
import com.icusu.sivan.domain.routing.BetaParam;
import com.icusu.sivan.domain.routing.IBetaParamRepository;
import com.icusu.sivan.domain.routing.PgVectorRoutingRepository;
import com.icusu.sivan.domain.routing.RouteResult;

import com.icusu.sivan.domain.shared.port.IEmbeddingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * PostgreSQL + pgvector 驱动的三层贝叶斯路由引擎。
 * <p>
 * 智能体和技能独立匹配，组合使用：
 * <ul>
 *   <li>Agent 路由: Tier 0 (精确缓存) → Tier 1 (语义+贝叶斯) → Tier 2 (Thompson采样) → Tier 3 (LLM创建)</li>
 *   <li>技能匹配: Tier 1 (语义) → Tier 2 (探索) → Tier 3 (LLM创建)</li>
 * </ul>
 */
@Component
public class PgRouteEngine {

    private static final Logger log = LoggerFactory.getLogger(PgRouteEngine.class);

    private final IAgentRepository agentRepo;
    private final IBetaParamRepository betaRepo;
    private final PgVectorRoutingRepository vectorRepo;
    private final IEmbeddingService embeddingService;
    private final DefaultModelRouter modelRouter;
    private final IntentClassifier intentClassifier;
    private final ISkillRepository skillRepository;

    private static final double TIER1_THRESHOLD = 0.7;
    /** 技能匹配阈值 — 基于技能本身的能力特征（name+tags+description），与任务无强关联时不绑定 */
    private static final double SKILL_MATCH_THRESHOLD = 0.5;
    private static final int TIER1_K = 10;
    private static final Random RANDOM = new Random();

    public PgRouteEngine(IAgentRepository agentRepo, IBetaParamRepository betaRepo,
                          PgVectorRoutingRepository vectorRepo, IEmbeddingService embeddingService,
                          DefaultModelRouter modelRouter,
                          ISkillRepository skillRepository,
                          IntentClassifier intentClassifier) {
        this.agentRepo = agentRepo;
        this.betaRepo = betaRepo;
        this.vectorRepo = vectorRepo;
        this.embeddingService = embeddingService;
        this.modelRouter = modelRouter;
        this.skillRepository = skillRepository;
        this.intentClassifier = intentClassifier;
    }

    /**
     * 为任务节点解析 Agent + 技能（组合式路由）。
     * <p>
     * 智能体和技能独立匹配，各自走 Tier 0/1/2/3 管道，结果组合后返回。
     */
    public Mono<RouteResult> resolve(UUID accountId, String taskContent, String featureHash) {
        // 意图判断不影响路由决策（仅用于调用方选择执行器类型）
        String intent = classifyInputType(taskContent, accountId);

        // Tier 0: 精确缓存（Agent + 技能合并结果）
        RouteResult r0 = tier0(accountId, featureHash);
        if (r0 != null) {
            log.debug("[路由] Tier 0 命中: agent={} confidence={}", r0.agentName(), r0.confidence());
            // Tier 0 仅命中 Agent，仍需独立匹配技能
            List<String> skillIds = resolveSkillsOnly(accountId, taskContent);
            return Mono.just(new RouteResult(r0.agentName(), r0.category(), 0, r0.confidence(), intent, skillIds));
        }

        // Tier 1/2/3: 异步执行，智能体 + 技能独立匹配后组合
        return Mono.fromCallable(() -> {
            // 1) 独立匹配智能体
            RouteResult agentResult = resolveAgentOnly(accountId, taskContent, featureHash);
            if (agentResult == null) return null;

            // 2) 独立匹配技能
            List<String> skillIds = resolveSkillsOnly(accountId, taskContent);

            // 3) 组合结果（保留原始意图标记）
            return new RouteResult(
                    agentResult.agentName(), agentResult.category(),
                    agentResult.tier(), agentResult.confidence(), intent,
                    skillIds
            );
        }).subscribeOn(Schedulers.boundedElastic());
    }

    // ============== 智能体匹配管道 ==============

    /** 独立智能体匹配：Tier 1 → Tier 2 → Agent 语义匹配 → Tier 3 (createAgent) */
    private RouteResult resolveAgentOnly(UUID accountId, String taskContent, String featureHash) {
        RouteResult r1 = tier1(accountId, taskContent);
        if (r1 != null) {
            log.debug("[路由] Tier 1 命中: agent={} confidence={}", r1.agentName(), r1.confidence());
            return r1;
        }
        RouteResult r2 = tier2(accountId, featureHash);
        if (r2 != null) {
            log.debug("[路由] Tier 2 探索: agent={} confidence={}", r2.agentName(), r2.confidence());
            return r2;
        }

        // Agent 语义匹配：在 Tier 3 创建前，搜索已有智能体是否可复用 — 防止重复创建
        try {
            float[] taskEmb = embeddingService.embed(taskContent);
            RouteResult existing = matchExistingAgent(accountId, taskEmb);
            if (existing != null) {
                log.info("[路由] 复用已有智能体(语义匹配): agent={} confidence={}",
                        existing.agentName(), String.format("%.2f", existing.confidence()));
                return existing;
            }
        } catch (Exception e) {
            log.warn("[路由] Agent 语义匹配异常: {}", e.getMessage());
        }

        RouteResult r3 = createAgent(accountId, taskContent);
        log.info("[路由] Tier 3 创建: agent={}", r3.agentName());
        return r3;
    }

    /** Tier 0: featureHash 精确命中。 */
    private RouteResult tier0(UUID accountId, String featureHash) {
        if (featureHash == null || featureHash.isBlank()) return null;
        List<BetaParam> params = betaRepo.findAllByKey(accountId, featureHash);
        for (BetaParam p : params) {
            if (p.expectation() >= TIER1_THRESHOLD
                    && agentRepo.findByAccountAndName(accountId, p.agentName())
                            .map(com.icusu.sivan.domain.agent.AgentDefinition::isActive).orElse(false)) {
                String cat = resolveCategory(accountId, p.agentName());
                return new RouteResult(p.agentName(), cat, 0, p.expectation());
            }
        }
        return null;
    }

    /** Tier 1: pgvector 语义检索 + 贝叶斯加权。 */
    private RouteResult tier1(UUID accountId, String taskContent) {
        try {
            float[] emb = embeddingService.embed(taskContent);
            List<PgVectorRoutingRepository.SimilarRoute> similar = vectorRepo.findSimilar(accountId, emb, TIER1_K);
            if (similar.isEmpty()) return null;

            // 按相似度加权计算每个 agent 的后验期望
            var agentExpectations = similar.stream()
                    .collect(java.util.stream.Collectors.groupingBy(
                            PgVectorRoutingRepository.SimilarRoute::agentName,
                            java.util.stream.Collectors.teeing(
                                    java.util.stream.Collectors.summingDouble(
                                            r -> r.similarity() * r.expectation()),
                                    java.util.stream.Collectors.summingDouble(
                                            PgVectorRoutingRepository.SimilarRoute::similarity),
                                    (weightedSum, totalWeight) -> totalWeight > 0
                                            ? weightedSum / totalWeight
                                            : 0.5)));

            return agentExpectations.entrySet().stream()
                    .filter(e -> e.getValue() >= TIER1_THRESHOLD)
                    .filter(e -> agentRepo.findByAccountAndName(accountId, e.getKey())
                            .map(com.icusu.sivan.domain.agent.AgentDefinition::isActive).orElse(false))
                    .max(java.util.Map.Entry.comparingByValue())
                    .map(e -> {
                        String cat = resolveCategory(accountId, e.getKey());
                        return new RouteResult(e.getKey(), cat, 1, e.getValue(), "task");
                    })
                    .orElse(null);
        } catch (Exception e) {
            log.warn("[路由] Tier 1 异常: {}", e.getMessage());
            return null;
        }
    }

    /** Tier 2: Thompson 采样探索。
     *  仅当该 featureHash 有 Beta 记录（即这类任务被尝试过）时才探索。
     *  无 Beta 记录时降级 Tier 3，避免将不相关的智能体强配给新任务。 */
    private RouteResult tier2(UUID accountId, String featureHash) {
        try {
            List<BetaParam> candidates = betaRepo.findAllByKey(accountId, featureHash);
            // 无 Beta 记录 → 从未见过这类任务 → 无探索基础 → 降级 Tier 3
            if (candidates.isEmpty()) {
                log.debug("[路由] Tier 2 无 Beta 记录，降级 Tier 3");
                return null;
            }

            candidates = candidates.stream()
                    .filter(p -> agentRepo.findByAccountAndName(accountId, p.agentName())
                            .map(com.icusu.sivan.domain.agent.AgentDefinition::isActive).orElse(false))
                    .toList();
            if (candidates.isEmpty()) return null;

            // Beta 采样：选采样值最大的 Agent
            String bestAgent = null;
            double bestSample = -1;
            for (BetaParam p : candidates) {
                double sample = betaSample(p.alpha(), p.beta());
                if (sample > bestSample) {
                    bestSample = sample;
                    bestAgent = p.agentName();
                }
            }
            if (bestAgent == null) return null;

            String cat = resolveCategory(accountId, bestAgent);
            return new RouteResult(bestAgent, cat, 2, bestSample);
        } catch (Exception e) {
            log.warn("[路由] Tier 2 异常: {}", e.getMessage());
            return null;
        }
    }

    /** Tier 3: LLM 创建新 Agent（含幂等性检查，避免重复创建和 duplicate key 错误）。
     *  注：技能绑定不由 createAgent 负责，由独立技能匹配管道 {@link #resolveSkillsOnly} 处理。 */
    private RouteResult createAgent(UUID accountId, String taskContent) {
        try {
            Model model = modelRouter.getDefaultModel(accountId);
            String prompt = AgentPrompts.agentCreateUser("general", taskContent).content();

            Model.ModelResponse resp = model.chat(
                    List.of(Msg.of(Role.USER, List.of(new Content.Text(prompt)))),
                    List.of(), Model.ModelParams.defaults()
            ).subscribeOn(Schedulers.boundedElastic()).block();

            String agentName;
            String category;
            String systemPrompt;
            String description;
            String craftDeclaration;
            if (resp != null && resp.msg() != null) {
                String json = resp.msg().text();
                agentName = extractJson(json, "displayName");
                category = extractJson(json, "category");
                systemPrompt = extractJson(json, "systemPrompt");
                description = extractJson(json, "description");
                craftDeclaration = extractJson(json, "craftDeclaration");
                if (agentName == null || agentName.isBlank()) {
                    log.warn("[路由] Tier 3 LLM 未返回 displayName，跳过创建");
                    return fallbackToExisting(accountId);
                }
                if (category == null || category.isBlank()) category = "general";
            } else {
                log.warn("[路由] Tier 3 LLM 无响应，跳过创建");
                return fallbackToExisting(accountId);
            }

            // ===== 幂等性检查：同名 Agent 已存在则不重复创建 =====
            var existingOpt = agentRepo.findByAccountAndName(accountId, agentName);
            if (existingOpt.isPresent()) {
                log.info("Tier 3 跳过创建(已存在): name={}", agentName);
                return new RouteResult(agentName, existingOpt.get().getCategory(), 3, 0.5);
            }

            // 持久化新 Agent（不绑定技能 — 技能由独立管道 resolveSkillsOnly 匹配）
            AgentDefinition agent = AgentDefinition.builder()
                    .accountId(accountId)
                    .agentName(agentName)
                    .displayName(agentName)
                    .description(description != null && !description.isBlank()
                            ? description : "「" + agentName + "」领域专家，擅长该领域的专业分析与执行")
                    .category(category)
                    .systemPrompt(systemPrompt != null && !systemPrompt.isBlank()
                            ? systemPrompt
                            : "你是一位通用的「" + agentName + "」领域助手，具备该领域的核心专业能力。")
                    .craftDeclaration(craftDeclaration)
                    .createdBy("system")
                    .build();
            agent.activate();

            try {
                agentRepo.save(agent);
                log.info("Tier 3 创建 Agent: name={} category={}", agentName, category);
            } catch (org.springframework.dao.DataIntegrityViolationException e) {
                // 并发竞争：另一线程刚刚创建了同名 Agent，使用已存在的记录
                var raceOpt = agentRepo.findByAccountAndName(accountId, agentName);
                if (raceOpt.isPresent()) {
                    log.info("Tier 3 并发冲突，使用已存在 Agent: name={}", agentName);
                    return new RouteResult(agentName, raceOpt.get().getCategory(), 3, 0.5);
                }
                throw e;
            }
            return new RouteResult(agentName, category, 3, 0.5);
        } catch (Exception e) {
            log.warn("[路由] Tier 3 失败: {}", e.getMessage());
            return fallbackToExisting(accountId);
        }
    }

    // ============== 技能匹配管道 ==============

    /**
     * 独立技能匹配 — 至少选择一个技能，按任务复杂度最多选 3-5 个。
     * <p>
     * 无技能时通过 LLM 创建一个（Tier 3），有技能时基于语义匹配选择最相关的多个技能。
     * 简单任务上限 3 个，复杂任务上限 5 个，至少保证 1 个。
     */
    private List<String> resolveSkillsOnly(UUID accountId, String taskContent) {
        List<Skill> allActive = skillRepository.findAllActiveByAccount(accountId);

        // 无技能 → 创建 1 个
        if (allActive.isEmpty()) {
            log.debug("[技能路由] 无可用技能，走 Tier 3 创建");
            String newId = createSkill(accountId, taskContent);
            return newId != null ? List.of(newId) : List.of();
        }

        // 判定任务复杂度（描述长度 < 80 或仅含简单关键词视为简单任务）
        boolean isComplex = isComplexTask(taskContent);
        int maxSkills = isComplex ? 5 : 3;
        log.debug("[技能路由] 任务复杂度: {}，最多选 {} 个技能", isComplex ? "复杂" : "简单", maxSkills);

        // Tier 1: 语义匹配（基于技能的 name + tags + description 等能力特征）
        try {
            float[] taskEmb = embeddingService.embed(taskContent);
            List<String> searchTexts = allActive.stream()
                    .map(this::buildSkillSearchText)
                    .toList();
            List<float[]> skillEmbs = embeddingService.embedBatch(searchTexts);

            if (skillEmbs != null && skillEmbs.size() == allActive.size()) {
                // 收集所有技能的分数
                List<SkillScore> scored = new java.util.ArrayList<>();
                for (int i = 0; i < allActive.size(); i++) {
                    if (skillEmbs.get(i) == null) continue;
                    double sim = cosineSimilarity(taskEmb, skillEmbs.get(i));
                    scored.add(new SkillScore(allActive.get(i).getSkillId().toString(), sim));
                }

                // 按相似度降序排列
                scored.sort((a, b) -> Double.compare(b.score, a.score));

                // 取所有超过阈值的技能，但不超过上限
                List<String> matched = scored.stream()
                        .filter(s -> s.score >= SKILL_MATCH_THRESHOLD)
                        .limit(maxSkills)
                        .map(s -> s.id)
                        .toList();

                // 至少保证 1 个（取最高分）
                if (matched.isEmpty() && !scored.isEmpty()) {
                    matched = List.of(scored.getFirst().id);
                }

                if (!matched.isEmpty()) {
                    log.debug("[技能路由] Tier 1 命中: skills={} count={} (complex={})",
                            matched, matched.size(), isComplex);
                    return matched;
                }
            }
        } catch (Exception e) {
            log.warn("[技能路由] Tier 1 异常: {}", e.getMessage());
        }

        // 兜底：至少选一个已有技能（最高分）
        if (!allActive.isEmpty()) {
            String fallback = allActive.getFirst().getSkillId().toString();
            log.debug("[技能路由] 兜底选择: skillId={}", fallback);
            return List.of(fallback);
        }
        return List.of();
    }

    /** 技能-分数对 */
    private record SkillScore(String id, double score) {}

    /**
     * 判定任务是否复杂。
     * 复杂条件：描述较长（>= 80 字符），或包含多个任务关键词/步骤标记。
     */
    private static boolean isComplexTask(String taskContent) {
        if (taskContent == null || taskContent.isBlank()) return false;
        if (taskContent.length() >= 80) return true;
        // 统计任务关键词出现次数
        int keywordCount = 0;
        String lower = taskContent.toLowerCase();
        for (String kw : PromptUtils.TASK_KEYWORDS) {
            int idx = 0;
            while ((idx = lower.indexOf(kw, idx)) != -1) {
                keywordCount++;
                idx += kw.length();
            }
        }
        return keywordCount >= 3;
    }

    /** Tier 3: LLM 创建新技能（仅无任何技能时调用）。 */
    private String createSkill(UUID accountId, String taskContent) {
        try {
            Model model = modelRouter.getDefaultModel(accountId);
            String prompt = SkillPrompts.taskCreatePrompt(taskContent);

            Model.ModelResponse resp = model.chat(
                    List.of(Msg.of(Role.USER, List.of(new Content.Text(prompt)))),
                    List.of(), Model.ModelParams.defaults()
            ).subscribeOn(Schedulers.boundedElastic()).block();

            if (resp == null || resp.msg() == null) return null;

            String json = resp.msg().text();
            String name = extractJson(json, "name");
            String displayName = extractJson(json, "displayName");
            String description = extractJson(json, "description");
            String category = extractJson(json, "category");
            String content = extractJson(json, "content");
            String tagsStr = extractJson(json, "tags");

            if (name == null || name.isBlank()) name = "custom_skill";
            if (displayName == null || displayName.isBlank()) displayName = name;
            if (category == null || category.isBlank()) category = "general";

            // 幂等性检查：同名技能已存在则不创建
            var existing = skillRepository.findByAccountAndName(accountId, name);
            if (existing.isPresent()) {
                log.info("[技能路由] Tier 3 跳过创建(已存在): name={}", name);
                return existing.get().getSkillId().toString();
            }

            Skill skill = Skill.builder()
                    .accountId(accountId)
                    .skillCode("skill_" + UUID.randomUUID().toString().substring(0, 8))
                    .name(name)
                    .displayName(displayName)
                    .description(description != null ? description : "")
                    .category(category)
                    .content(content != null ? content : "")
                    .tags(parseTags(tagsStr))
                    .skillType(SkillType.SYSTEM)
                    .status(SkillStatus.ACTIVE)
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();
            skill.activate();
            skillRepository.save(skill);
            log.info("[技能路由] Tier 3 创建技能: name={} category={}", name, category);
            return skill.getSkillId().toString();
        } catch (Exception e) {
            log.warn("[技能路由] Tier 3 创建失败: {}", e.getMessage());
            return null;
        }
    }

    /** 解析 JSON 标签数组为 List<String>。 */
    private static List<String> parseTags(String tagsStr) {
        if (tagsStr == null || tagsStr.isBlank()) return List.of();
        try {
            List<String> tags = new java.util.ArrayList<>();
            String content = tagsStr.trim();
            if (content.startsWith("[") && content.endsWith("]")) {
                content = content.substring(1, content.length() - 1);
            }
            for (String part : content.split(",")) {
                String tag = part.trim().replaceAll("^\"|\"$", "");
                if (!tag.isEmpty()) tags.add(tag);
            }
            return tags;
        } catch (Exception e) {
            return List.of();
        }
    }

    /** Tier 3 失败时降级：使用任意已存在的智能体。 */
    private RouteResult fallbackToExisting(UUID accountId) {
        List<AgentDefinition> existing = agentRepo.findAllByAccount(accountId);
        if (!existing.isEmpty()) {
            AgentDefinition a = existing.getFirst();
            return new RouteResult(a.getAgentName(), a.getCategory(), 3, 0.3);
        }
        return null;
    }

    /**
     * 对已有智能体做语义匹配 — 防止 Tier 3 为相似任务重复创建智能体。
     * <p>
     * 将任务 embedding 与每个智能体的 (displayName + description + systemPrompt + category)
     * 做余弦相似度对比，最佳匹配且高于阈值时复用该智能体。
     *
     * @param accountId 账户 ID
     * @param taskEmb   任务 embedding
     * @return 匹配结果（Tier=1 表示语义层匹配），无匹配时返回 null
     */
    private RouteResult matchExistingAgent(UUID accountId, float[] taskEmb) {
        List<AgentDefinition> agents = agentRepo.findAllByAccount(accountId);
        if (agents.isEmpty()) {
            log.debug("[Agent 语义匹配] 无已有智能体");
            return null;
        }

        // 构建每个 agent 的搜索文本
        List<String> searchTexts = agents.stream()
                .map(this::buildAgentSearchText)
                .toList();

        // 批量嵌入 agent 搜索文本
        List<float[]> agentEmbs = embeddingService.embedBatch(searchTexts);
        if (agentEmbs == null || agentEmbs.size() != agents.size()) {
            log.warn("[Agent 语义匹配] embedBatch 返回不符合预期");
            return null;
        }

        // 找最佳匹配
        String bestAgentName = null;
        double bestScore = 0;
        for (int i = 0; i < agents.size(); i++) {
            if (agentEmbs.get(i) == null) continue;
            double sim = cosineSimilarity(taskEmb, agentEmbs.get(i));
            if (sim > bestScore) {
                bestScore = sim;
                bestAgentName = agents.get(i).getAgentName();
            }
        }

        // 阈值 0.6 — 低于 Tier 1 的 0.7，因为 agent 描述比历史路由记录更简短
        double AGENT_MATCH_THRESHOLD = 0.6;
        if (bestScore >= AGENT_MATCH_THRESHOLD && bestAgentName != null) {
            String cat = resolveCategory(accountId, bestAgentName);
            return new RouteResult(bestAgentName, cat, 1, bestScore);
        }

        log.debug("[Agent 语义匹配] 无超阈值匹配(最高={})", String.format("%.2f", bestScore));
        return null;
    }

    /** 构建智能体搜索文本（displayName + description + systemPrompt + category）。 */
    private String buildAgentSearchText(AgentDefinition agent) {
        StringBuilder sb = new StringBuilder();
        if (agent.getDisplayName() != null) sb.append(agent.getDisplayName()).append(" ");
        if (agent.getDescription() != null) sb.append(agent.getDescription()).append(" ");
        if (agent.getSystemPrompt() != null) sb.append(agent.getSystemPrompt()).append(" ");
        if (agent.getCategory() != null) sb.append(agent.getCategory());
        return sb.toString().trim();
    }

    // ============== 工具方法 ==============

    /** Beta 采样：用 Gamma 近似。 */
    static double betaSample(int alpha, int beta) {
        double x = sampleGamma(alpha);
        double y = sampleGamma(beta);
        return x / (x + y);
    }

    private static double sampleGamma(int shape) {
        // 简单 Gamma 近似（Marsaglia & Tsang 方法简化版）
        if (shape <= 1) {
            double u = RANDOM.nextDouble();
            return -Math.log(u);
        }
        double d = shape - 1.0 / 3;
        double c = 1.0 / Math.sqrt(9 * d);
        while (true) {
            double x, v;
            do {
                x = RANDOM.nextGaussian();
                v = 1 + c * x;
            } while (v <= 0);
            v = v * v * v;
            double u = RANDOM.nextDouble();
            if (u < 1 - 0.0331 * (x * x) * (x * x)) return d * v;
            if (Math.log(u) < 0.5 * x * x + d * (1 - v + Math.log(v))) return d * v;
        }
    }

    /** 解析 Agent 的 category。 */
    private String resolveCategory(UUID accountId, String agentName) {
        return agentRepo.findByAccountAndName(accountId, agentName)
                .map(AgentDefinition::getCategory)
                .orElse("general");
    }

    /** 构造技能搜索文本（name + tags + description）用于语义匹配。 */
    private String buildSkillSearchText(Skill skill) {
        StringBuilder sb = new StringBuilder(skill.getName());
        if (skill.getTags() != null && !skill.getTags().isEmpty()) {
            sb.append(" ").append(String.join(" ", skill.getTags()));
        }
        if (skill.getDescription() != null && !skill.getDescription().isBlank()) {
            sb.append(" ").append(skill.getDescription());
        }
        return sb.toString();
    }

    /** 余弦相似度。 */
    private static double cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) return 0;
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        return (normA > 0 && normB > 0) ? dot / (Math.sqrt(normA) * Math.sqrt(normB)) : 0;
    }


    /** 计算 md5 特征哈希。 */
    public static String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(32);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(input.hashCode());
        }
    }

    /**
     * 从 LLM 返回的 JSON 中提取字段值。
     * 处理 markdown 代码块包裹、空格、多行值。
     */
    @SuppressWarnings("unchecked")
    private static String extractJson(String json, String key) {
        if (json == null || json.isBlank()) return null;
        try {
            // 去掉 markdown 代码块标记
            String cleaned = json.trim();
            if (cleaned.startsWith("```")) {
                int start = cleaned.indexOf('\n');
                int end = cleaned.lastIndexOf("```");
                if (start > 0 && end > start) {
                    cleaned = cleaned.substring(start, end).trim();
                }
            }
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            java.util.Map<String, Object> map = mapper.readValue(cleaned,
                    new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, Object>>() {});
            Object val = map.get(key);
            if (val == null) return null;
            // 数组类型（如 tags）转为 JSON 字符串
            if (val instanceof java.util.List) {
                return mapper.writeValueAsString(val);
            }
            return val.toString();
        } catch (Exception e) {
            log.debug("extractJson 解析失败: key={} error={}", key, e.getMessage());
            return null;
        }
    }

    /** 判断输入意图：chat（简单对话）或 task（需要工具/多步推理），记录分类日志。 */
    private String classifyInputType(String taskDescription, UUID accountId) {
        return intentClassifier.isTask(taskDescription, accountId) ? "task" : "chat";
    }

    private static String truncateStr(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

}
