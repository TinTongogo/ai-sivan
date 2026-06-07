package com.icusu.sivan.infra.agent.adapter;

import com.icusu.sivan.common.enums.AgentType;
import com.icusu.sivan.domain.agent.AgentDefinition;
import com.icusu.sivan.domain.agent.IAgentRepository;
import com.icusu.sivan.infra.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Agent 仓储适配器集成测试。
 */
@Sql("/disable-fk.sql")
@Transactional
class AgentRepositoryAdapterTest extends AbstractIntegrationTest {

    @Autowired
    private IAgentRepository agentRepository;

    /** 保存 Agent 后能通过 ID 查询到。 */
    @Test
    void shouldSaveAndFindById() {
        UUID accountId = UUID.randomUUID();
        AgentDefinition config = AgentDefinition.builder()
                .accountId(accountId)
                .agentName("test-agent")
                .displayName("测试 Agent")
                .description("用于测试")
                .systemPrompt("你是一个测试助手")
                .agentType(AgentType.USER)
                .build();
        agentRepository.save(config);

        assertNotNull(config.getAgentId());

        AgentDefinition found = agentRepository.findById(config.getAgentId()).orElse(null);
        assertNotNull(found);
        assertEquals("test-agent", found.getAgentName());
        assertEquals("测试 Agent", found.getDisplayName());
        assertEquals(accountId, found.getAccountId());
    }

    /** 按账号和名称查询 Agent。 */
    @Test
    void shouldFindByAccountAndName() {
        UUID accountId = UUID.randomUUID();
        AgentDefinition config = AgentDefinition.builder()
                .accountId(accountId)
                .agentName("unique-agent")
                .displayName("唯一 Agent")
                .systemPrompt("prompt")
                .agentType(AgentType.USER)
                .build();
        agentRepository.save(config);

        AgentDefinition found = agentRepository.findByAccountAndName(accountId, "unique-agent").orElse(null);
        assertNotNull(found);
        assertEquals("unique-agent", found.getAgentName());
    }

    /** 按账号列出所有 Agent。 */
    @Test
    void shouldListAllByAccount() {
        UUID accountId = UUID.randomUUID();
        for (int i = 0; i < 3; i++) {
            AgentDefinition config = AgentDefinition.builder()
                    .accountId(accountId)
                    .agentName("agent-" + i)
                    .displayName("Agent " + i)
                    .systemPrompt("prompt")
                    .agentType(AgentType.USER)
                    .build();
            agentRepository.save(config);
        }

        List<AgentDefinition> agents = agentRepository.findAllByAccount(accountId);
        assertEquals(3, agents.size());
    }

    /** 按项目过滤 Agent。 */
    @Test
    void shouldFilterByProject() {
        UUID accountId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();

        AgentDefinition withProject = AgentDefinition.builder()
                .accountId(accountId).projectId(projectId)
                .agentName("project-agent").displayName("项目 Agent")
                .systemPrompt("prompt").agentType(AgentType.USER)
                .build();
        agentRepository.save(withProject);

        AgentDefinition withoutProject = AgentDefinition.builder()
                .accountId(accountId)
                .agentName("no-project-agent").displayName("无项目 Agent")
                .systemPrompt("prompt").agentType(AgentType.USER)
                .build();
        agentRepository.save(withoutProject);

        List<AgentDefinition> projectAgents = agentRepository.findAllByAccountAndProject(accountId, projectId);
        assertEquals(1, projectAgents.size());
        assertEquals("project-agent", projectAgents.get(0).getAgentName());
    }

    /** version 为手动管理字段，持久化后值不变。 */
    @Test
    void shouldPreserveVersion() {
        UUID accountId = UUID.randomUUID();
        AgentDefinition config = AgentDefinition.builder()
                .accountId(accountId)
                .agentName("version-test")
                .displayName("版本测试")
                .systemPrompt("prompt")
                .agentType(AgentType.USER)
                .build();
        agentRepository.save(config);
        Integer savedVersion = config.getVersion();

        config.setDisplayName("更新后名称");
        agentRepository.save(config);

        AgentDefinition updated = agentRepository.findById(config.getAgentId()).orElse(null);
        assertNotNull(updated);
        // version 为手动管理字段，未显式递增则保持不变
        assertEquals(savedVersion, updated.getVersion());
    }

    /** 删除 Agent。 */
    @Test
    void shouldDelete() {
        UUID accountId = UUID.randomUUID();
        AgentDefinition config = AgentDefinition.builder()
                .accountId(accountId)
                .agentName("delete-test")
                .displayName("删除测试")
                .systemPrompt("prompt")
                .agentType(AgentType.USER)
                .build();
        agentRepository.save(config);

        UUID agentId = config.getAgentId();
        agentRepository.delete(agentId);

        assertTrue(agentRepository.findById(agentId).isEmpty());
    }

    /** 按类型统计 Agent 数量。 */
    @Test
    void shouldCountByType() {
        UUID accountId = UUID.randomUUID();
        for (int i = 0; i < 3; i++) {
            AgentDefinition config = AgentDefinition.builder()
                    .accountId(accountId)
                    .agentName("user-agent-" + i)
                    .displayName("用户 Agent " + i)
                    .systemPrompt("prompt")
                    .agentType(AgentType.USER)
                    .build();
            agentRepository.save(config);
        }
        AgentDefinition sysAgent = AgentDefinition.builder()
                .accountId(accountId)
                .agentName("system-agent")
                .displayName("系统 Agent")
                .systemPrompt("prompt")
                .agentType(AgentType.SYSTEM)
                .build();
        agentRepository.save(sysAgent);

        var counts = agentRepository.countByType(accountId);
        assertEquals(3L, counts.get("USER"));
        assertEquals(1L, counts.get("SYSTEM"));
    }
}
