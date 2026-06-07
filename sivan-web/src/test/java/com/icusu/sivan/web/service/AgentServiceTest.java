package com.icusu.sivan.web.agent.service;

import com.icusu.sivan.common.exception.DomainException;
import com.icusu.sivan.domain.agent.AgentDefinition;
import com.icusu.sivan.domain.agent.IAgentRepository;
import com.icusu.sivan.web.agent.dto.CreateAgentRequest;
import com.icusu.sivan.web.agent.dto.UpdateAgentRequest;
import com.icusu.sivan.web.agent.dto.AgentResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
/**
 * 智能体服务测试。
 */
class AgentServiceTest {

    @Mock
    private IAgentRepository agentRepository;

    private AgentService agentService;

    private final UUID accountId = UUID.randomUUID();

    /**
     * 初始化测试环境。
     */
    @BeforeEach
    void setUp() {
        agentService = new AgentService(agentRepository);
    }

    /**
     * 创建智能体应成功。
     */
    @Test
    void create_shouldSucceed() {
        CreateAgentRequest request = new CreateAgentRequest();
        request.setAgentName("code-reviewer");
        request.setDisplayName("代码审查员");
        request.setDescription("审查代码质量");
        request.setSystemPrompt("你是一个代码审查员");

        when(agentRepository.findByAccountAndName(accountId, "code-reviewer")).thenReturn(Optional.empty());

        AgentResponse response = agentService.create(accountId, request);

        assertEquals("code-reviewer", response.getAgentName());
        assertEquals("代码审查员", response.getDisplayName());
        assertEquals("USER", response.getAgentType());
        assertEquals("ACTIVE", response.getStatus());
        assertEquals(1, response.getVersion());
        verify(agentRepository).save(any(AgentDefinition.class));
    }

    /**
     * 创建智能体时名称重复应抛出异常。
     */
    @Test
    void create_shouldThrowWhenNameExists() {
        CreateAgentRequest request = new CreateAgentRequest();
        request.setAgentName("duplicate");
        request.setDisplayName("重复");
        request.setSystemPrompt("prompt");

        when(agentRepository.findByAccountAndName(accountId, "duplicate"))
                .thenReturn(Optional.of(new AgentDefinition()));

        assertThrows(DomainException.class, () -> agentService.create(accountId, request));
        verify(agentRepository, never()).save(any());
    }

    /**
     * 根据 ID 查询智能体应返回正确结果。
     */
    @Test
    void getById_shouldReturnAgent() {
        UUID agentId = UUID.randomUUID();
        AgentDefinition config = AgentDefinition.builder()
                .agentId(agentId).accountId(accountId).agentName("test")
                .displayName("测试").build();

        when(agentRepository.findById(agentId)).thenReturn(Optional.of(config));

        AgentResponse response = agentService.getById(accountId, agentId);

        assertEquals("test", response.getAgentName());
    }

    /**
     * 查询非当前账户的智能体应抛出异常。
     */
    @Test
    void getById_shouldThrowWhenNotOwned() {
        UUID agentId = UUID.randomUUID();
        AgentDefinition config = AgentDefinition.builder()
                .agentId(agentId).accountId(UUID.randomUUID()).build();

        when(agentRepository.findById(agentId)).thenReturn(Optional.of(config));

        assertThrows(DomainException.class, () -> agentService.getById(accountId, agentId));
    }

    /**
     * 未指定项目时应返回所有智能体。
     */
    @Test
    void list_shouldReturnAllWhenNoProject() {
        AgentDefinition a1 = AgentDefinition.builder()
                .agentId(UUID.randomUUID()).accountId(accountId).agentName("A1").build();
        when(agentRepository.findAllByAccount(accountId)).thenReturn(List.of(a1));

        List<AgentResponse> list = agentService.list(accountId, null);

        assertEquals(1, list.size());
        verify(agentRepository).findAllByAccount(accountId);
    }

    /**
     * 指定项目时应按项目过滤智能体。
     */
    @Test
    void list_shouldFilterByProject() {
        UUID projectId = UUID.randomUUID();
        when(agentRepository.findAllByAccountAndProject(accountId, projectId))
                .thenReturn(List.of());

        List<AgentResponse> list = agentService.list(accountId, projectId);

        assertTrue(list.isEmpty());
        verify(agentRepository).findAllByAccountAndProject(accountId, projectId);
    }

    /**
     * 更新智能体应递增版本号。
     */
    @Test
    void update_shouldIncrementVersion() {
        UUID agentId = UUID.randomUUID();
        AgentDefinition config = AgentDefinition.builder()
                .agentId(agentId).accountId(accountId).agentName("old")
                .displayName("旧名称").version(1).build();

        when(agentRepository.findById(agentId)).thenReturn(Optional.of(config));

        UpdateAgentRequest request = new UpdateAgentRequest();
        request.setDisplayName("新名称");

        AgentResponse response = agentService.update(accountId, agentId, request);

        assertEquals("新名称", response.getDisplayName());
        assertEquals(2, response.getVersion());
    }

    /**
     * 删除智能体应委托仓库。
     */
    @Test
    void delete_shouldRemoveAgent() {
        UUID agentId = UUID.randomUUID();
        AgentDefinition config = AgentDefinition.builder()
                .agentId(agentId).accountId(accountId).build();

        when(agentRepository.findById(agentId)).thenReturn(Optional.of(config));

        agentService.delete(accountId, agentId);

        verify(agentRepository).delete(agentId);
    }

    /**
     * 获取类型分布应返回各类型计数。
     */
    @Test
    void getTypeDistribution_shouldReturnCounts() {
        when(agentRepository.countByType(accountId))
                .thenReturn(Map.of("USER", 5L, "SYSTEM", 2L));

        Map<String, Long> distribution = agentService.getTypeDistribution(accountId);

        assertEquals(5L, distribution.get("USER"));
        assertEquals(2L, distribution.get("SYSTEM"));
    }
}
