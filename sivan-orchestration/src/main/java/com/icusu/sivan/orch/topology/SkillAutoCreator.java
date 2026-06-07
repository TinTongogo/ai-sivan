package com.icusu.sivan.orch.topology;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.icusu.sivan.agent.prompt.PromptTemplate;
import com.icusu.sivan.agent.prompt.SkillPrompts;
import com.icusu.sivan.common.enums.SkillType;
import com.icusu.sivan.core.message.Msg;
import com.icusu.sivan.core.message.Role;
import com.icusu.sivan.agent.model.ModelRouter;
import com.icusu.sivan.core.model.Model.ModelParams;
import com.icusu.sivan.domain.agent.AgentDefinition;
import com.icusu.sivan.domain.agent.Skill;
import com.icusu.sivan.domain.agent.IAgentRepository;
import com.icusu.sivan.domain.agent.ISkillRepository;
import com.icusu.sivan.domain.shared.util.CosineSimilarity;
import com.icusu.sivan.infra.knowledge.EmbeddingService;
import reactor.core.publisher.Mono;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 自动创建 SYSTEM 类型技能。
 * 解析智能体的 systemPrompt / craftDeclaration，通过 LLM 推断需要哪些技能。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SkillAutoCreator {

    private final ModelRouter modelRouter;
    private final ISkillRepository skillRepository;
    private final IAgentRepository agentRepository;
    private final EmbeddingService embeddingService;
    private final ObjectMapper objectMapper;

    /** 技能语义匹配阈值：cosine similarity ≥ 此值视为同一技能，复用而非创建。 */
    private static final double SKILL_SEMANTIC_THRESHOLD = 0.7;

    /**
     * 为指定智能体自动创建缺失的技能，并绑定到智能体。
     *
     * @param agentConfig 智能体配置（会被修改 skillIds）
     * @param accountId   账户 ID
     * @param projectId   项目 ID
     */
    public Mono<Void> createForAgent(AgentDefinition agentConfig, UUID accountId, UUID projectId) {
        String systemPrompt = agentConfig.getSystemPrompt();
        String craftDeclaration = agentConfig.getCraftDeclaration();
        if (systemPrompt == null || systemPrompt.isBlank()) {
            log.debug("智能体无 systemPrompt，跳过技能创建: {}", agentConfig.getAgentName());
            return Mono.empty();
        }

        // 1. LLM 推断需要的技能（基于角色能力而非任务描述）
        PromptTemplate template = SkillPrompts.SKILL_CREATE_TEMPLATE;
        java.util.Map<String, Object> variables = new java.util.HashMap<>();
        variables.put("agentName", agentConfig.getAgentName());
        variables.put("category", agentConfig.getCategory() != null ? agentConfig.getCategory() : "");
        variables.put("craftDeclaration", craftDeclaration != null ? craftDeclaration : "");
        variables.put("systemPrompt", systemPrompt);
        String prompt = template.render(variables).content();

        return modelRouter.getDefaultModel(accountId).chat(
                List.of(
                        Msg.of(Role.SYSTEM, SkillPrompts.SKILL_CREATE_SYSTEM.content()),
                        Msg.of(Role.USER, prompt)
                ),
                ModelParams.defaults()
        ).flatMap(response -> {
            String llmResponse = response != null ? response.msg().text() : "";

            List<Skill> skillsToCreate = parseSkills(llmResponse);
            if (skillsToCreate.isEmpty()) {
                log.debug("LLM 未生成技能，跳过: agentName={}", agentConfig.getAgentName());
                return Mono.empty();
            }

            List<String> newSkillIds = new ArrayList<>();
            if (agentConfig.getSkillIds() != null) {
                newSkillIds.addAll(agentConfig.getSkillIds());
            }

            // 预加载已有技能（用于语义匹配）
            List<Skill> existingSkills = skillRepository.findAllByAccount(accountId);

            for (Skill skill : skillsToCreate) {
                // 1. 精确 skillCode 匹配
                var existing = skillRepository.findByAccountAndCode(accountId, skill.getSkillCode());
                if (existing.isPresent()) {
                    linkSkill(newSkillIds, existing.get());
                    continue;
                }

                // 2. 精确名称匹配
                var existingByName = skillRepository.findByAccountAndName(accountId, skill.getName());
                if (existingByName.isPresent()) {
                    linkSkill(newSkillIds, existingByName.get());
                    continue;
                }

                // 3. 语义相似度匹配 — 复用已有技能而非创建
                Skill matched = findSemanticMatch(skill, existingSkills);
                if (matched != null) {
                    linkSkill(newSkillIds, matched);
                    continue;
                }

                // 4. 无匹配 → 创建新技能
                skill.setAccountId(accountId);
                skill.setProjectId(projectId);
                skill.setSkillType(SkillType.SYSTEM);
                skillRepository.save(skill);
                newSkillIds.add(skill.getSkillId().toString());
                log.info("自动创建技能: name={}, skillId={}", skill.getName(), skill.getSkillId());
            }

            agentConfig.setSkillIds(newSkillIds);
            agentRepository.save(agentConfig);
            log.info("已绑定 {} 个技能到智能体: agentName={}", newSkillIds.size(), agentConfig.getAgentName());
            return Mono.empty();
        });
    }

    /** 将已有技能 ID 加入 agent 列表（去重）。 */
    private static void linkSkill(List<String> newSkillIds, Skill skill) {
        String id = skill.getSkillId().toString();
        if (!newSkillIds.contains(id)) {
            newSkillIds.add(id);
        }
    }

    /**
     * 对拟创建技能与已有技能做语义匹配。
     * 若匹配到相似技能（cosine similarity ≥ {@link #SKILL_SEMANTIC_THRESHOLD}），
     * 返回已有技能；否则返回 null。
     */
    private Skill findSemanticMatch(Skill proposed, List<Skill> existingSkills) {
        if (existingSkills.isEmpty()) return null;

        String probe = (proposed.getName() != null ? proposed.getName() : "")
                + " " + (proposed.getDescription() != null ? proposed.getDescription() : "")
                + " " + (proposed.getCategory() != null ? proposed.getCategory() : "");

        if (probe.isBlank()) return null;

        try {
            float[] probeVec = embeddingService.embed(probe);

            // 批量 embedding 所有已有技能，避免逐个 HTTP 请求
            List<String> existingProfiles = existingSkills.stream()
                    .map(s -> (s.getName() != null ? s.getName() : "")
                            + " " + (s.getDescription() != null ? s.getDescription() : "")
                            + " " + (s.getCategory() != null ? s.getCategory() : "")
                            + " " + (s.getContent() != null ? s.getContent() : ""))
                    .toList();
            List<float[]> existingVecs = embeddingService.embedBatch(existingProfiles);

            Skill best = null;
            double bestSim = 0;

            for (int i = 0; i < existingSkills.size() && i < existingVecs.size(); i++) {
                float[] existingVec = existingVecs.get(i);
                if (existingVec == null) continue;
                double sim = CosineSimilarity.compute(probeVec, existingVec);

                if (sim > bestSim) {
                    bestSim = sim;
                    best = existingSkills.get(i);
                }
            }

            if (best != null && bestSim >= SKILL_SEMANTIC_THRESHOLD) {
                log.info("技能语义匹配，复用已有技能: proposed={}, matched={}, similarity={}",
                        proposed.getName(), best.getName(), String.format("%.4f", bestSim));
                return best;
            }
        } catch (Exception e) {
            log.warn("技能语义匹配异常，将直接创建: {}", e.getMessage());
        }
        return null;
    }

    /** 解析 LLM 返回的 JSON 技能列表。 */
    private List<Skill> parseSkills(String response) {
        if (response == null || response.isBlank()) return List.of();
        int start = response.indexOf('[');
        int end = response.lastIndexOf(']');
        if (start < 0 || end < 0) return List.of();

        try {
            var array = objectMapper.readTree(response.substring(start, end + 1));
            List<Skill> skills = new ArrayList<>();
            for (var node : array) {
                Skill skill = Skill.builder()
                        .skillCode(node.has("skillCode") ? node.get("skillCode").asText() : "auto_" + skills.size())
                        .name(node.has("name") ? node.get("name").asText() : "技能" + skills.size())
                        .displayName(node.has("displayName") ? node.get("displayName").asText() : null)
                        .description(node.has("description") ? node.get("description").asText() : null)
                        .category(node.has("category") ? node.get("category").asText() : null)
                        .tags(node.has("tags") && node.get("tags").isArray()
                                ? java.util.stream.StreamSupport.stream(node.get("tags").spliterator(), false)
                                        .map(com.fasterxml.jackson.databind.JsonNode::asText).toList()
                                : null)
                        .content(node.has("content") ? node.get("content").asText() : "")
                        .build();
                if (skill.getSkillCode() != null && !skill.getSkillCode().isBlank()) {
                    skills.add(skill);
                }
            }
            return skills;
        } catch (Exception e) {
            log.warn("技能列表解析失败: {}", e.getMessage());
            return List.of();
        }
    }
}
