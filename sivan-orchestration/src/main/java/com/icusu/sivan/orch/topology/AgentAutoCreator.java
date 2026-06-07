package com.icusu.sivan.orch.topology;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.icusu.sivan.agent.prompt.AgentPrompts;
import com.icusu.sivan.common.enums.AgentType;
import com.icusu.sivan.core.message.Msg;
import com.icusu.sivan.core.message.Role;
import com.icusu.sivan.agent.model.ModelRouter;
import com.icusu.sivan.core.model.Model.ModelParams;
import com.icusu.sivan.domain.agent.AgentDefinition;
import com.icusu.sivan.domain.agent.IAgentRepository;
import com.icusu.sivan.domain.shared.util.CosineSimilarity;
import com.icusu.sivan.infra.knowledge.EmbeddingService;
import reactor.core.publisher.Mono;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * 自动创建 SYSTEM 类型智能体。
 * 根据 agentName + taskType 通过 LLM 推断 systemPrompt、description、craftDeclaration。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentAutoCreator {

    private final ModelRouter modelRouter;
    private final IAgentRepository agentRepository;
    private final EmbeddingService embeddingService;
    private final ObjectMapper objectMapper;

    /** 智能体语义匹配阈值：与 AgentSkillMatcher 保持一致。 */
    private static final double AGENT_SEMANTIC_THRESHOLD = 0.65;

    /**
     * 自动创建智能体（含阶段上下文，使生成的 systemPrompt 贴合阶段职责）。
     *
     * @param agentName      智能体名称
     * @param taskType       任务类型描述
     * @param phaseContext   该 Agent 负责的阶段描述（如"构思故事整体框架""设计角色形象"），可为 null
     * @param accountId      账户 ID
     * @param projectId      项目 ID
     * @return 已存在或新创建的智能体
     */
    public Mono<AgentDefinition> create(String agentName, String taskType, String phaseContext, UUID accountId, UUID projectId) {
        // 1. 去重
        var existing = agentRepository.findByAccountAndName(accountId, agentName);
        if (existing.isPresent()) {
            log.debug("智能体已存在，跳过创建: {}", agentName);
            return Mono.just(existing.get());
        }

        // 1.5 语义匹配 — 防御：SquadCreator 层 AgentSkillMatcher 可能因 embedding 异常或阈值未命中而放过
        AgentDefinition semanticMatch = findSemanticMatch(agentName, taskType, phaseContext, accountId);
        if (semanticMatch != null) {
            log.info("语义匹配到已有智能体，跳过 LLM 创建: request={}, matched={}", agentName, semanticMatch.getAgentName());
            return Mono.just(semanticMatch);
        }

        // 2. LLM 推断配置
        String prompt = AgentPrompts.agentCreateUser(taskType, agentName, phaseContext).content();

        return modelRouter.getDefaultModel(accountId).chat(
                List.of(
                        Msg.of(Role.SYSTEM, AgentPrompts.AGENT_CREATE_SYSTEM.content()),
                        Msg.of(Role.USER, prompt)
                ),
                ModelParams.defaults()
        ).map(response -> {
            String llmResponse = response != null ? response.msg().text() : "";

            // 3. 解析 LLM 响应
            String displayName = agentName;
            String description = taskType + " 智能体";
            String systemPrompt = AgentPrompts.agentFallbackIdentity(agentName).content();
            String craftDeclaration = agentName + " 专业技能";
            String category = null;

            try {
                var json = objectMapper.readTree(extractJson(llmResponse));
                if (json.has("displayName")) displayName = sanitizeName(json.get("displayName").asText(), 128);
                if (json.has("description")) description = truncate(json.get("description").asText(), 500);
                if (json.has("systemPrompt")) systemPrompt = json.get("systemPrompt").asText();
                if (json.has("craftDeclaration")) craftDeclaration = truncate(json.get("craftDeclaration").asText(), 200);
                if (json.has("category")) category = sanitizeName(json.get("category").asText(), 32);
            } catch (Exception e) {
                log.warn("LLM 返回格式解析失败，使用默认值: agentName={}", agentName);
            }

            // 4. 构建 AgentDefinition
            AgentDefinition config = AgentDefinition.builder()
                    .accountId(accountId)
                    .projectId(projectId)
                    .agentName(agentName)
                    .displayName(displayName)
                    .description(description)
                    .systemPrompt(systemPrompt)
                    .craftDeclaration(craftDeclaration)
                    .category(category)
                    .agentType(AgentType.DYNAMIC)
                    .build();

            agentRepository.save(config);
            log.info("自动创建智能体: agentName={}, agentId={}", agentName, config.getAgentId());
            return config;
        });
    }

    /**
     * 语义匹配：将请求的 agentName + taskType + phaseContext 与已有智能体做 embedding 对比。
     * 匹配成功返回已有智能体，否则返回 null。
     */
    private AgentDefinition findSemanticMatch(String agentName, String taskType, String phaseContext, UUID accountId) {
        String probe = taskType + " " + agentName + (phaseContext != null ? " " + phaseContext : "");
        if (probe.isBlank()) return null;

        try {
            List<AgentDefinition> allAgents = agentRepository.findAllByAccount(accountId);
            if (allAgents.isEmpty()) return null;

            float[] probeVec = embeddingService.embed(probe);

            // 批量 embedding 所有已有 agent 画像，避免逐个 HTTP 请求
            List<String> profiles = allAgents.stream()
                    .map(a -> a.getAgentName()
                            + " " + (a.getDescription() != null ? a.getDescription() : "")
                            + " " + (a.getCraftDeclaration() != null ? a.getCraftDeclaration() : ""))
                    .toList();
            List<float[]> agentVecs = embeddingService.embedBatch(profiles);

            AgentDefinition best = null;
            double bestSim = 0;

            for (int i = 0; i < allAgents.size() && i < agentVecs.size(); i++) {
                float[] agentVec = agentVecs.get(i);
                if (agentVec == null) continue;
                double sim = CosineSimilarity.compute(probeVec, agentVec);

                if (sim > bestSim) {
                    bestSim = sim;
                    best = allAgents.get(i);
                }
            }

            if (best != null && bestSim >= AGENT_SEMANTIC_THRESHOLD) {
                return best;
            }
            log.debug("AgentAutoCreator 语义未命中: agentName={}, bestSim={}", agentName, String.format("%.4f", bestSim));
        } catch (Exception e) {
            log.warn("AgentAutoCreator 语义匹配异常，将直接创建: {}", e.getMessage());
        }
        return null;
    }

    /** 从 LLM 回复中提取 JSON 块。 */
    private static String extractJson(String text) {
        if (text == null || text.isBlank()) return "{}";
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end < 0) return "{}";
        return text.substring(start, end + 1);
    }

    /** 白名单字符集：字母、数字、中文、常见标点。 */
    private static final java.util.regex.Pattern SAFE_NAME = java.util.regex.Pattern.compile("[\\w\\u4e00-\\u9fff\\-_. ]+");

    /** 对 LLM 输出的名称做截断和字符过滤，防止 XSS 和 DB 错误。 */
    private static String sanitizeName(String name, int maxLen) {
        if (name == null || name.isBlank()) return name;
        String cleaned = name.length() > maxLen ? name.substring(0, maxLen) : name;
        return SAFE_NAME.matcher(cleaned).matches() ? cleaned : cleaned.replaceAll("[^\\w\\u4e00-\\u9fff\\-_. ]", "");
    }

    private static String truncate(String text, int maxLen) {
        if (text == null || text.isBlank()) return text;
        return text.length() > maxLen ? text.substring(0, maxLen) : text;
    }
}
