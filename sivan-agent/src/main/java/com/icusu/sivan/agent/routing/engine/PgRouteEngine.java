package com.icusu.sivan.agent.routing.engine;

import com.icusu.sivan.agent.model.DefaultModelRouter;
import com.icusu.sivan.agent.prompt.AgentPrompts;
import com.icusu.sivan.core.message.Content;
import com.icusu.sivan.core.message.Msg;
import com.icusu.sivan.core.message.Role;
import com.icusu.sivan.core.model.Model;
import com.icusu.sivan.domain.agent.AgentDefinition;
import com.icusu.sivan.domain.agent.IAgentRepository;
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
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * PostgreSQL + pgvector 驱动的三层贝叶斯路由引擎。
 * <p>
 * Tier 0: featureHash 精确命中 → O(1)
 * Tier 1: pgvector HNSW 语义检索 + 贝叶斯加权 → ~5ms
 * Tier 2: Thompson 采样探索 → < 1ms
 * Tier 3: LLM 创建新 Agent → ~1-3s
 */
@Component
public class PgRouteEngine {

    private static final Logger log = LoggerFactory.getLogger(PgRouteEngine.class);

    private final IAgentRepository agentRepo;
    private final IBetaParamRepository betaRepo;
    private final PgVectorRoutingRepository vectorRepo;
    private final IEmbeddingService embeddingService;
    private final DefaultModelRouter modelRouter;

    private static final double TIER1_THRESHOLD = 0.7;
    private static final int TIER1_K = 10;
    private static final Random RANDOM = new Random();

    public PgRouteEngine(IAgentRepository agentRepo, IBetaParamRepository betaRepo,
                          PgVectorRoutingRepository vectorRepo, IEmbeddingService embeddingService,
                          DefaultModelRouter modelRouter) {
        this.agentRepo = agentRepo;
        this.betaRepo = betaRepo;
        this.vectorRepo = vectorRepo;
        this.embeddingService = embeddingService;
        this.modelRouter = modelRouter;
    }

    /**
     * 为任务节点解析 Agent。
     *
     * @param accountId    账户 ID
     * @param taskContent  节点任务内容
     * @param featureHash  特征哈希（md5(TaskFeatures)）
     * @return 路由结果
     */
    public Mono<RouteResult> resolve(UUID accountId, String taskContent, String featureHash) {
        // 先判断意图：chat 路径直接返回，不走路由
        String intent = classifyInputType(taskContent);
        if ("chat".equals(intent)) {
            log.debug("[路由] chat 意图: content={}", truncateStr(taskContent, 50));
            return Mono.just(new RouteResult(null, null, -1, 0, "chat"));
        }

        // Task 路径：三层路由
        // Tier 0: 精确缓存
        RouteResult r0 = tier0(accountId, featureHash);
        if (r0 != null) {
            log.debug("[路由] Tier 0 命中: agent={} confidence={}", r0.agentName(), r0.confidence());
            return Mono.just(new RouteResult(r0.agentName(), r0.category(), 0, r0.confidence(), "task"));
        }

        // Tier 1: 语义检索 + 贝叶斯加权
        return Mono.fromCallable(() -> {
            RouteResult r1 = tier1(accountId, taskContent);
            if (r1 != null) {
                log.debug("[路由] Tier 1 命中: agent={} confidence={}", r1.agentName(), r1.confidence());
                return r1;
            }

            // Tier 2: Thompson 采样探索
            RouteResult r2 = tier2(accountId, featureHash);
            if (r2 != null) {
                log.debug("[路由] Tier 2 探索: agent={} confidence={}", r2.agentName(), r2.confidence());
                return r2;
            }

            // Tier 3: LLM 创建新 Agent
            RouteResult r3 = createAgent(accountId, taskContent);
            log.info("[路由] Tier 3 创建: agent={}", r3.agentName());
            return r3;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /** Tier 0: featureHash 精确命中。 */
    private RouteResult tier0(UUID accountId, String featureHash) {
        if (featureHash == null || featureHash.isBlank()) return null;
        List<BetaParam> params = betaRepo.findAllByKey(accountId, featureHash);
        for (BetaParam p : params) {
            if (p.expectation() >= TIER1_THRESHOLD) {
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

    /** Tier 2: Thompson 采样探索。 */
    private RouteResult tier2(UUID accountId, String featureHash) {
        try {
            List<BetaParam> candidates = betaRepo.findAllByKey(accountId, featureHash);
            if (candidates.isEmpty()) {
                // 无 Beta 记录，使用账户下所有活跃 Agent 作为候选
                List<AgentDefinition> agents = agentRepo.findAllByAccount(accountId);
                if (agents.isEmpty()) return null;
                candidates = agents.stream()
                        .map(a -> BetaParam.prior(a.getAgentName()))
                        .toList();
            }

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

    /** Tier 3: LLM 创建新 Agent。 */
    private RouteResult createAgent(UUID accountId, String taskContent) {
        try {
            Model model = modelRouter.getDefaultModel(accountId);
            String prompt = AgentPrompts.agentCreateUser("general", "auto", taskContent).content();

            Model.ModelResponse resp = model.chat(
                    List.of(Msg.of(Role.USER, List.of(new Content.Text(prompt)))),
                    List.of(), Model.ModelParams.defaults()
            ).block();

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
                if (agentName == null || agentName.isBlank()) agentName = "通用助手";
                if (category == null || category.isBlank()) category = "general";
            } else {
                agentName = "通用助手";
                category = "general";
                systemPrompt = null;
                description = null;
                craftDeclaration = null;
            }

            // 持久化新 Agent
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
            agentRepo.save(agent);
            log.info("Tier 3 创建 Agent: name={} category={}", agentName, category);
            return new RouteResult(agentName, category, 3, 0.5);
        } catch (Exception e) {
            log.warn("[路由] Tier 3 失败，使用兜底 Agent: {}", e.getMessage());
            // 兜底：确保至少有一个通用助手
            List<AgentDefinition> existing = agentRepo.findAllByAccount(accountId);
            if (!existing.isEmpty()) {
                AgentDefinition a = existing.getFirst();
                return new RouteResult(a.getAgentName(), a.getCategory(), 3, 0.3);
            }
            AgentDefinition fallback = AgentDefinition.builder()
                    .accountId(accountId)
                    .agentName("通用助手")
                    .displayName("通用助手")
                    .description("可处理各类通用任务，无特定领域限制")
                    .category("general")
                    .systemPrompt("你是一位通用的智能助手，擅长理解用户意图并完成各类任务。")
                    .createdBy("system")
                    .build();
            fallback.activate();
            agentRepo.save(fallback);
            return new RouteResult("通用助手", "general", 3, 0.3);
        }
    }

    // ===== 工具方法 =====

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

    /** 简单 JSON 值提取。 */
    private static String extractJson(String json, String key) {
        String search = "\"" + key + "\":\"";
        int start = json.indexOf(search);
        if (start < 0) return null;
        start += search.length();
        int end = json.indexOf("\"", start);
        return end > start ? json.substring(start, end) : null;
    }

    /** 判断输入意图：chat（简单对话）或 task（需要工具/多步推理）。 */
    private static String classifyInputType(String taskDescription) {
        if (taskDescription == null || taskDescription.isBlank()) return "chat";
        String lower = taskDescription.toLowerCase();
        for (String kw : TASK_KEYWORDS) {
            if (lower.contains(kw)) return "task";
        }
        return "chat";
    }

    private static String truncateStr(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    private static final List<String> TASK_KEYWORDS = List.of(
            "分析", "处理", "生成", "创建", "执行", "查找", "计算", "翻译", "总结",
            "提取", "转换", "合并", "拆分", "比较", "统计", "预测", "优化",
            "analyze", "process", "generate", "create", "execute", "find", "calculate",
            "translate", "summarize", "extract", "convert", "merge", "split", "compare",
            "写", "改", "删", "查", "修", "重构", "设计", "实现", "测试", "部署",
            "write", "edit", "delete", "query", "fix", "refactor", "design", "implement",
            "test", "deploy"
    );
}
