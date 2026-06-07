package com.icusu.sivan.orch.topology;

import com.icusu.sivan.domain.agent.AgentDefinition;
import com.icusu.sivan.domain.agent.Skill;
import com.icusu.sivan.domain.agent.IAgentRepository;
import com.icusu.sivan.domain.agent.ISkillRepository;
import com.icusu.sivan.domain.shared.util.CosineSimilarity;
import com.icusu.sivan.infra.knowledge.EmbeddingService;
import lombok.Builder;
import lombok.Value;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * 智能体能力匹配器。
 * 将阶段能力需求与已有智能体的能力画像（systemPrompt + craftDeclaration + skills）做语义相似度匹配，
 * 优先复用已有智能体而非创建新的。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentSkillMatcher {

    private final EmbeddingService embeddingService;
    private final IAgentRepository agentRepository;
    private final ISkillRepository skillRepository;

    /** 匹配阈值：cosine similarity ≥ 此值视为匹配。 */
    private static final double MATCH_THRESHOLD = 0.65;

    /**
     * 匹配结果。
     */
    @Value
    @Builder
    public static class MatchResult {
        boolean matched;
        AgentDefinition agent;
        double similarity;
    }

    public static MatchResult noMatch() {
        return MatchResult.builder().matched(false).build();
    }

    public static MatchResult matched(AgentDefinition agent, double similarity) {
        return MatchResult.builder().matched(true).agent(agent).similarity(similarity).build();
    }

    /**
     * 对能力需求文本进行智能体匹配。
     *
     * @param capabilityRequirement 阶段能力需求描述（如"审查代码安全漏洞，识别SQL注入"）
     * @param accountId             当前账户 ID
     * @return 匹配结果，matched=true 时含最佳匹配的智能体和相似度
     */
    public MatchResult match(String capabilityRequirement, UUID accountId) {
        if (capabilityRequirement == null || capabilityRequirement.isBlank()) {
            return noMatch();
        }

        List<AgentDefinition> agents = agentRepository.findAllByAccount(accountId);
        if (agents.isEmpty()) {
            return noMatch();
        }

        try {
            // 对能力需求计算一次 embedding
            float[] reqVector = embeddingService.embed(capabilityRequirement);

            // 批量 embedding 所有 agent 画像，避免逐个 HTTP 请求
            List<String> profiles = agents.stream()
                    .map(this::buildCapabilityProfile)
                    .toList();
            List<float[]> agentVectors = embeddingService.embedBatch(profiles);

            AgentDefinition bestAgent = null;
            double bestSimilarity = 0;

            for (int i = 0; i < agents.size() && i < agentVectors.size(); i++) {
                float[] agentVector = agentVectors.get(i);
                if (agentVector == null) continue;
                double similarity = CosineSimilarity.compute(reqVector, agentVector);

                if (similarity > bestSimilarity) {
                    bestSimilarity = similarity;
                    bestAgent = agents.get(i);
                }
            }

            if (bestAgent != null && bestSimilarity >= MATCH_THRESHOLD) {
                log.info("智能体匹配成功: requirement={}, agent={}, similarity={}",
                        truncate(capabilityRequirement), bestAgent.getAgentName(),
                        String.format("%.4f", bestSimilarity));
                return matched(bestAgent, bestSimilarity);
            }

            log.debug("智能体匹配失败: requirement={}, bestSimilarity={}",
                    truncate(capabilityRequirement), String.format("%.4f", bestSimilarity));
        } catch (Exception e) {
            log.warn("Embedding 服务不可用，跳过语义匹配，将自动创建新智能体: {}", e.getMessage());
        }
        return noMatch();
    }

    /**
     * 构建智能体的能力画像文本，用于语义匹配。
     * 包含 systemPrompt（核心行为定义）、craftDeclaration（技能声明）、关联 skills 内容。
     */
    private String buildCapabilityProfile(AgentDefinition agent) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("Agent: ").append(agent.getAgentName()).append("\n");
        sb.append("Description: ").append(agent.getDescription() != null ? agent.getDescription() : "").append("\n");
        sb.append("System Prompt: ").append(agent.getSystemPrompt() != null ? agent.getSystemPrompt() : "").append("\n");
        sb.append("Craft Declaration: ").append(agent.getCraftDeclaration() != null ? agent.getCraftDeclaration() : "").append("\n");

        if (agent.getSkillIds() != null && !agent.getSkillIds().isEmpty()) {
            sb.append("Skills:\n");
            for (String skillIdStr : agent.getSkillIds()) {
                try {
                    Skill skill = skillRepository.findById(UUID.fromString(skillIdStr)).orElse(null);
                    if (skill != null) {
                        sb.append("- ").append(skill.getName() != null ? skill.getName() : "unknown")
                                .append(": ").append(skill.getContent() != null ? skill.getContent() : "")
                                .append("\n");
                    }
                } catch (Exception e) {
                    log.warn("读取技能失败: skillId={}, agent={}", skillIdStr, agent.getAgentName());
                }
            }
        }

        return sb.toString();
    }

    private static String truncate(String s) {
        return s != null && s.length() > 60 ? s.substring(0, 60) + "..." : s;
    }
}
