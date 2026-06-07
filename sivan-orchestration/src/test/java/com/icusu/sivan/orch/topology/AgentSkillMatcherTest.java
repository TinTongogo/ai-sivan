package com.icusu.sivan.orch.topology;

import com.icusu.sivan.domain.agent.AgentDefinition;
import com.icusu.sivan.domain.agent.Skill;
import com.icusu.sivan.domain.agent.IAgentRepository;
import com.icusu.sivan.domain.agent.ISkillRepository;
import com.icusu.sivan.infra.knowledge.EmbeddingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentSkillMatcherTest {

    @Mock
    private EmbeddingService embeddingService;
    @Mock
    private IAgentRepository agentRepository;
    @Mock
    private ISkillRepository skillRepository;

    private AgentSkillMatcher matcher;
    private final UUID accountId = UUID.randomUUID();
    private AgentDefinition securityAgent;
    private AgentDefinition writingAgent;

    /** Qwen3 2048 维向量：方向 X, Y, Z 互为正交，便于测试相似度计算。 */
    private static final float[] VEC_X = new float[2048];
    private static final float[] VEC_Y = new float[2048];
    private static final float[] VEC_Z = new float[2048];
    static {
        VEC_X[0] = 1;
        VEC_Y[1] = 1;
        VEC_Z[2] = 1;
    }

    @BeforeEach
    void setUp() {
        matcher = new AgentSkillMatcher(embeddingService, agentRepository, skillRepository);

        securityAgent = AgentDefinition.builder()
                .agentId(UUID.randomUUID())
                .agentName("security_reviewer")
                .description("代码安全审查，发现SQL注入、XSS等漏洞")
                .systemPrompt("你是一名资深安全工程师，负责审查代码中的安全漏洞。")
                .craftDeclaration("擅长发现Web应用安全漏洞")
                .skillIds(List.of(UUID.randomUUID().toString()))
                .build();

        writingAgent = AgentDefinition.builder()
                .agentId(UUID.randomUUID())
                .agentName("creative_writer")
                .description("文学创作，包括故事构思、角色设计和叙事")
                .systemPrompt("你是一名创意写作专家，擅长构思故事框架和塑造角色。")
                .craftDeclaration("擅长小说和剧本创作")
                .build();
    }

    /**
     * Answer：Agent profile 和 req 都返回 VEC_X → 完全匹配。
     */
    private final Answer<float[]> allX = inv -> VEC_X;

    /**
     * Answer：输入包含 "Agent:" 视为 profile → VEC_X，否则需求 → VEC_Z（正交，不匹配）。
     */
    private Answer<float[]> reqInZ() {
        return inv -> ((String) inv.getArgument(0)).contains("Agent:") ? VEC_X : VEC_Z;
    }

    @Test
    void match_shouldReturnAgent_whenCapabilityMatches() {
        when(agentRepository.findAllByAccount(accountId)).thenReturn(List.of(securityAgent, writingAgent));
        // 两个 agent 画像都与需求同向，security_reviewer 排序在前应被选中
        when(embeddingService.embed(anyString())).thenAnswer(allX);
        when(embeddingService.embedBatch(anyList())).thenReturn(List.of(VEC_X, VEC_X));

        AgentSkillMatcher.MatchResult result = matcher.match("审查代码安全漏洞，发现SQL注入", accountId);

        assertTrue(result.isMatched());
        assertEquals("security_reviewer", result.getAgent().getAgentName());
        assertEquals(1.0, result.getSimilarity(), 0.001);
    }

    @Test
    void match_shouldReturnNoMatch_whenNoCapabilityMatches() {
        when(agentRepository.findAllByAccount(accountId)).thenReturn(List.of(securityAgent, writingAgent));
        // 需求与所有 agent 画像正交
        when(embeddingService.embed(anyString())).thenAnswer(reqInZ());

        AgentSkillMatcher.MatchResult result = matcher.match("写一首关于春天的诗歌", accountId);

        assertFalse(result.isMatched());
    }

    @Test
    void match_shouldReturnNoMatch_whenNoAgentsExist() {
        when(agentRepository.findAllByAccount(accountId)).thenReturn(List.of());

        AgentSkillMatcher.MatchResult result = matcher.match("任何需求", accountId);

        assertFalse(result.isMatched());
    }

    @Test
    void match_shouldReturnNoMatch_whenRequirementIsBlank() {
        AgentSkillMatcher.MatchResult result = matcher.match("", accountId);

        assertFalse(result.isMatched());
    }

    @Test
    void match_shouldReturnNoMatch_whenRequirementIsNull() {
        AgentSkillMatcher.MatchResult result = matcher.match(null, accountId);

        assertFalse(result.isMatched());
    }

    @Test
    void match_shouldIncludeSkillsInProfile() {
        Skill sqlSkill = Skill.builder()
                .skillId(UUID.fromString(securityAgent.getSkillIds().get(0)))
                .name("sql_injection_check")
                .content("检查SQL注入漏洞，包括参数化查询验证和输入过滤检查")
                .build();

        when(agentRepository.findAllByAccount(accountId)).thenReturn(List.of(securityAgent));
        when(skillRepository.findById(UUID.fromString(securityAgent.getSkillIds().get(0))))
                .thenReturn(Optional.of(sqlSkill));
        when(embeddingService.embed(anyString())).thenAnswer(allX);
        when(embeddingService.embedBatch(anyList())).thenReturn(List.of(VEC_X));

        AgentSkillMatcher.MatchResult result = matcher.match("审查SQL注入漏洞", accountId);

        assertTrue(result.isMatched());
    }

    @Test
    void match_shouldReturnNoMatch_whenEmbeddingServiceFails() {
        when(agentRepository.findAllByAccount(accountId)).thenReturn(List.of(securityAgent));
        when(embeddingService.embed(anyString())).thenThrow(new RuntimeException("服务不可用"));

        AgentSkillMatcher.MatchResult result = matcher.match("代码审查", accountId);
        assertFalse(result.isMatched());
    }
}
