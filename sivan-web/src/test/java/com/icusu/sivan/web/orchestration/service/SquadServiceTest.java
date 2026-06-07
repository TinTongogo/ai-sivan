package com.icusu.sivan.web.orchestration.service;

import com.icusu.sivan.common.exception.DomainException;
import com.icusu.sivan.orch.topology.TopologyGenerator;
import com.icusu.sivan.domain.agent.IAgentRepository;
import com.icusu.sivan.domain.orchestration.IContractRepository;
import com.icusu.sivan.domain.orchestration.IHitlReviewRepository;
import com.icusu.sivan.domain.orchestration.ISquadExecutionRepository;
import com.icusu.sivan.domain.orchestration.ISquadRepository;
import com.icusu.sivan.domain.orchestration.Squad;
import com.icusu.sivan.domain.orchestration.SquadExecution;
import com.icusu.sivan.domain.pipeline.IPipelineStepRepository;
import com.icusu.sivan.orch.executor.SquadExecutionEngine;
import com.icusu.sivan.orch.executor.SquadExecutionEvent;
import com.icusu.sivan.web.orchestration.dto.CreateSquadRequest;
import com.icusu.sivan.web.orchestration.dto.ExecuteSquadRequest;
import com.icusu.sivan.web.orchestration.dto.PhaseNodeRequest;
import com.icusu.sivan.web.orchestration.dto.UpdateSquadRequest;
import com.icusu.sivan.web.orchestration.dto.ContractResponse;
import com.icusu.sivan.web.orchestration.dto.SquadExecutionResponse;
import com.icusu.sivan.web.orchestration.dto.SquadResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
/** Squad 服务测试。 */
class SquadServiceTest {

    @Mock
    IAgentRepository agentRepository;
    @Mock
    private ISquadRepository squadRepository;
    @Mock
    private ISquadExecutionRepository squadExecutionRepository;
    @Mock
    private IContractRepository contractRepository;
    @Mock
    private IHitlReviewRepository hitlReviewRepository;
    @Mock
    private SquadExecutionEngine executionEngine;
    @Mock
    private ContractService contractService;
    @Mock
    private TopologyGenerator topologyGenerator;
    @Mock
    private IPipelineStepRepository pipelineStepRepository;

    private SquadService squadService;

    private final UUID accountId = UUID.randomUUID();

    @BeforeEach
    /** 初始化测试环境。 */
    void setUp() {
        squadService = new SquadService(squadRepository, squadExecutionRepository,
                contractRepository, hitlReviewRepository, agentRepository, contractService,
                executionEngine, topologyGenerator, pipelineStepRepository);
    }

    @Test
    /** 创建 Squad 成功。 */
    void create_shouldSucceed() {
        CreateSquadRequest request = new CreateSquadRequest();
        request.setName("测试 Squad");
        request.setDescription("这是一个测试");
        request.setMode("SEQUENTIAL");

        SquadResponse response = squadService.create(accountId, request);

        assertEquals("测试 Squad", response.getName());
        assertEquals("SEQUENTIAL", response.getMode());
        assertEquals(0, response.getUsageCount());
        verify(squadRepository).save(any(Squad.class));
    }

    @Test
    /** 创建 Squad 并映射阶段。 */
    void create_withPhases_shouldMapPhases() {
        PhaseNodeRequest phaseReq = new PhaseNodeRequest();
        phaseReq.setPhase(0);
        phaseReq.setName("代码审查");
        phaseReq.setAgents(List.of("reviewer"));

        CreateSquadRequest request = new CreateSquadRequest();
        request.setName("审查 Squad");
        request.setPhases(List.of(phaseReq));

        SquadResponse response = squadService.create(accountId, request);

        assertEquals(1, response.getPhases().size());
        assertEquals("代码审查", response.getPhases().get(0).getName());
        assertEquals(List.of("reviewer"), response.getPhases().get(0).getAgents());
    }

    @Test
    /** 根据 ID 获取 Squad。 */
    void getById_shouldReturnSquad() {
        UUID squadId = UUID.randomUUID();
        Squad squad = Squad.builder()
                .squadId(squadId).accountId(accountId).name("我的Squad").build();

        when(squadRepository.findById(squadId)).thenReturn(Optional.of(squad));

        SquadResponse response = squadService.getById(accountId, squadId);

        assertEquals("我的Squad", response.getName());
    }

    @Test
    /** 获取非本人 Squad 应抛出异常。 */
    void getById_shouldThrowWhenNotOwned() {
        UUID squadId = UUID.randomUUID();
        Squad squad = Squad.builder()
                .squadId(squadId).accountId(UUID.randomUUID()).build();

        when(squadRepository.findById(squadId)).thenReturn(Optional.of(squad));

        assertThrows(DomainException.class, () -> squadService.getById(accountId, squadId));
    }

    @Test
    /** 列出所有 Squad。 */
    void list_shouldReturnAllWhenNoProject() {
        Squad s = Squad.builder().squadId(UUID.randomUUID()).accountId(accountId).build();
        when(squadRepository.findAllByAccount(accountId)).thenReturn(List.of(s));

        List<SquadResponse> list = squadService.list(accountId, null);

        assertEquals(1, list.size());
    }

    @Test
    /** 按项目过滤 Squad 列表。 */
    void list_shouldFilterByProject() {
        UUID projectId = UUID.randomUUID();
        when(squadRepository.findAllByAccountAndProject(accountId, projectId))
                .thenReturn(List.of());

        List<SquadResponse> list = squadService.list(accountId, projectId);

        assertTrue(list.isEmpty());
    }

    @Test
    /** 更新 Squad 信息。 */
    void update_shouldModifyFields() {
        UUID squadId = UUID.randomUUID();
        Squad squad = Squad.builder()
                .squadId(squadId).accountId(accountId).name("旧名称").build();

        when(squadRepository.findById(squadId)).thenReturn(Optional.of(squad));

        UpdateSquadRequest request = new UpdateSquadRequest();
        request.setName("新名称");

        SquadResponse response = squadService.update(accountId, squadId, request);

        assertEquals("新名称", response.getName());
        verify(squadRepository).update(squad);
    }

    @Test
    /** 删除 Squad。 */
    void delete_shouldRemoveSquad() {
        UUID squadId = UUID.randomUUID();
        Squad squad = Squad.builder()
                .squadId(squadId).accountId(accountId).build();

        when(squadRepository.findById(squadId)).thenReturn(Optional.of(squad));
        when(squadExecutionRepository.findBySquadId(squadId)).thenReturn(List.of());
        doNothing().when(squadExecutionRepository).deleteBySquadId(any(UUID.class));

        squadService.delete(accountId, squadId);

        verify(squadRepository).delete(squadId);
    }

    @Test
    /** 获取 Squad 执行记录。 */
    void getExecutions_shouldReturnList() {
        UUID squadId = UUID.randomUUID();
        SquadExecution exec = SquadExecution.builder()
                .executionId(UUID.randomUUID()).squadId(squadId).build();

        when(squadExecutionRepository.findBySquadId(squadId)).thenReturn(List.of(exec));

        List<SquadExecutionResponse> executions = squadService.getExecutions(squadId);

        assertEquals(1, executions.size());
    }

    @Test
    /** 获取 Squad 执行详情。 */
    void getExecution_shouldReturnExecution() {
        UUID execId = UUID.randomUUID();
        UUID squadId = UUID.randomUUID();
        SquadExecution exec = SquadExecution.builder()
                .executionId(execId).squadId(squadId).build();
        Squad squad = Squad.builder()
                .squadId(squadId).accountId(accountId).build();

        when(squadExecutionRepository.findById(execId)).thenReturn(Optional.of(exec));
        when(squadRepository.findById(squadId)).thenReturn(Optional.of(squad));

        SquadExecutionResponse response = squadService.getExecution(accountId, execId);

        assertEquals(execId, response.getExecutionId());
    }

    @Test
    /** 获取 Squad 执行合约列表。 */
    void getContracts_shouldReturnList() {
        UUID execId = UUID.randomUUID();
        ContractResponse contractResp = ContractResponse.builder()
                .contractId(UUID.randomUUID()).sourceAgent("agent-a").phase(0).build();

        UUID accountId = UUID.randomUUID();
        when(contractService.getByExecution(execId, accountId)).thenReturn(List.of(contractResp));

        List<ContractResponse> contracts = squadService.getContracts(execId, accountId);

        assertEquals(1, contracts.size());
        assertEquals("agent-a", contracts.get(0).getSourceAgent());
    }

    @Test
    /** 执行 Squad 并触发异步流程。 */
    void execute_shouldCreateExecutionAndTriggerAsync() {
        UUID squadId = UUID.randomUUID();
        Squad squad = Squad.builder()
                .squadId(squadId).accountId(accountId)
                .name("测试Squad").usageCount(0).build();

        when(squadRepository.findById(squadId)).thenReturn(Optional.of(squad));
        doAnswer(invocation -> {
            SquadExecution exec = invocation.getArgument(0);
            exec.setExecutionId(UUID.randomUUID());
            return null;
        }).when(squadExecutionRepository).save(any(SquadExecution.class));

        ExecuteSquadRequest request = new ExecuteSquadRequest();
        request.setTaskDescription("写一个Hello World程序");

        SquadExecutionResponse response = squadService.execute(accountId, squadId, request);

        assertNotNull(response.getExecutionId());
        assertEquals("写一个Hello World程序", response.getTaskDescription());
        assertEquals("PENDING", response.getStatus());
        verify(executionEngine).execute(any(Squad.class), any(SquadExecution.class), eq(accountId));
    }

    @Test
    /** 流式获取 Squad 执行事件。 */
    void streamEvents_shouldReturnEventFlux() {
        UUID execId = UUID.randomUUID();
        UUID squadId = UUID.randomUUID();
        SquadExecution exec = SquadExecution.builder()
                .executionId(execId).squadId(squadId).build();
        Squad squad = Squad.builder()
                .squadId(squadId).accountId(accountId).build();

        when(squadExecutionRepository.findById(execId)).thenReturn(Optional.of(exec));
        when(squadRepository.findById(squadId)).thenReturn(Optional.of(squad));
        when(executionEngine.events(execId))
                .thenReturn(Flux.just(
                        new SquadExecutionEvent(execId, "RUNNING", 0, "阶段1", "开始"),
                        new SquadExecutionEvent(execId, "COMPLETED", 0, null, "完成")
                ));

        List<SquadExecutionEvent> events = squadService.streamEvents(accountId, execId).collectList().block();

        assertNotNull(events);
        assertEquals(2, events.size());
        assertEquals("RUNNING", events.get(0).getStatus());
        assertEquals("COMPLETED", events.get(1).getStatus());
    }
}
