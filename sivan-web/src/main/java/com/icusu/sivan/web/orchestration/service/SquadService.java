package com.icusu.sivan.web.orchestration.service;

import com.icusu.sivan.common.dto.PageResponse;
import com.icusu.sivan.common.enums.ExecutionStatus;
import com.icusu.sivan.common.enums.SquadMode;
import com.icusu.sivan.common.exception.ResourceNotFoundException;
import com.icusu.sivan.common.util.OwnershipValidator;
import com.icusu.sivan.domain.orchestration.PhaseNode;
import com.icusu.sivan.domain.orchestration.Squad;
import com.icusu.sivan.domain.orchestration.SquadExecution;
import com.icusu.sivan.domain.orchestration.SquadFactory;
import com.icusu.sivan.domain.agent.IAgentRepository;
import com.icusu.sivan.domain.orchestration.IContractRepository;
import com.icusu.sivan.domain.orchestration.IHitlReviewRepository;
import com.icusu.sivan.domain.orchestration.ISquadExecutionRepository;
import com.icusu.sivan.domain.orchestration.ISquadRepository;
import com.icusu.sivan.domain.pipeline.IPipelineStepRepository;
import com.icusu.sivan.orch.executor.SquadExecutionEngine;
import com.icusu.sivan.orch.executor.SquadExecutionEvent;
import com.icusu.sivan.orch.topology.TopologyGenerator;
import com.icusu.sivan.orch.topology.TopologyResult;
import com.icusu.sivan.web.orchestration.dto.CreateSquadRequest;
import com.icusu.sivan.web.orchestration.dto.ExecuteSquadRequest;
import com.icusu.sivan.web.orchestration.dto.GenerateTopologyRequest;
import com.icusu.sivan.web.orchestration.dto.UpdateSquadRequest;
import com.icusu.sivan.web.orchestration.dto.ContractResponse;
import com.icusu.sivan.web.orchestration.dto.DashboardEvent;
import com.icusu.sivan.web.orchestration.dto.ExecutionStatsResponse;
import com.icusu.sivan.web.orchestration.dto.PhaseNodeResponse;
import com.icusu.sivan.web.orchestration.dto.SquadExecutionResponse;
import com.icusu.sivan.web.orchestration.dto.SquadResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/** Squad 协作执行服务，管理智能体小组的拓扑生成、执行与监控。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SquadService {

    private final ISquadRepository squadRepository;
    private final ISquadExecutionRepository squadExecutionRepository;
    private final IContractRepository contractRepository;
    private final IHitlReviewRepository hitlReviewRepository;
    private final IAgentRepository agentRepository;
    private final ContractService contractService;
    private final SquadExecutionEngine executionEngine;
    private final TopologyGenerator topologyGenerator;
    private final IPipelineStepRepository pipelineStepRepository;

    /**
     * 根据任务描述自动生成 Squad 拓扑。
     */
    public Mono<TopologyResult> generateTopology(UUID accountId, GenerateTopologyRequest request) {
        return topologyGenerator.generate(accountId, request.getTaskDescription());
    }

    /** 创建 Squad。 */
    public SquadResponse create(UUID accountId, CreateSquadRequest request) {
        SquadMode mode = request.getMode() != null ? SquadMode.valueOf(request.getMode()) : SquadMode.SEQUENTIAL;

        List<PhaseNode> phases = request.getPhases() != null
                ? request.getPhases().stream().map(p -> PhaseNode.builder()
                .phase(p.getPhase())
                .name(p.getName())
                .mode(p.getMode() != null ? SquadMode.valueOf(p.getMode()) : SquadMode.SEQUENTIAL)
                .agents(resolveAgentNames(p.getAgents(), accountId))
                .description(p.getDescription())
                .inputFilter(p.getInputFilter())
                .outputFilter(p.getOutputFilter())
                .hitlMode(p.getHitlMode())
                .hitlAgents(p.getHitlAgents())
                .build()).toList()
                : List.of();

        Squad squad = SquadFactory.createUserSquad(accountId, request.getProjectId(),
                request.getName(), request.getDescription(), mode, phases);

        squadRepository.save(squad);
        return toResponse(squad);
    }

    /** 根据 ID 查询 Squad。 */
    public SquadResponse getById(UUID accountId, UUID squadId) {
        return toResponse(findOwned(accountId, squadId));
    }

    /** 查询 Squad 列表。 */
    public List<SquadResponse> list(UUID accountId, UUID projectId) {
        List<Squad> squads = projectId != null
                ? squadRepository.findAllByAccountAndProject(accountId, projectId)
                : squadRepository.findAllByAccount(accountId);
        return squads.stream().map(this::toResponse).toList();
    }

    /** 分页查询 Squad 列表（可指定项目 ID 和来源）。 */
    public PageResponse<SquadResponse> listPage(UUID accountId, int page, int size, UUID projectId, String source) {
        List<SquadResponse> items = (projectId != null
                ? squadRepository.findAllByAccountAndProjectPage(accountId, projectId, page, size)
                : squadRepository.findAllByAccountPage(accountId, page, size))
                .stream().map(this::toResponse).toList();
        if (source != null) {
            items = items.stream().filter(s -> source.equals(s.getSource())).toList();
        }
        long total = squadRepository.countByAccount(accountId);
        return PageResponse.of(items, total, page + 1, size);
    }

    /** 分页查询 Squad 列表（仅按项目过滤，保留向后兼容）。 */
    public PageResponse<SquadResponse> listPage(UUID accountId, int page, int size, UUID projectId) {
        return listPage(accountId, page, size, projectId, null);
    }

    /** 更新 Squad 配置。 */
    public SquadResponse update(UUID accountId, UUID squadId, UpdateSquadRequest request) {
        Squad squad = findOwned(accountId, squadId);
        List<PhaseNode> phases = request.getPhases() != null
                ? request.getPhases().stream().map(p -> PhaseNode.builder()
                    .phase(p.getPhase()).name(p.getName())
                    .mode(p.getMode() != null ? SquadMode.valueOf(p.getMode()) : SquadMode.SEQUENTIAL)
                    .agents(resolveAgentNames(p.getAgents(), squad.getAccountId()))
                    .description(p.getDescription()).inputFilter(p.getInputFilter())
                    .outputFilter(p.getOutputFilter())
                    .hitlMode(p.getHitlMode())
                    .hitlAgents(p.getHitlAgents()).build()).toList()
                : null;
        squad.updateFrom(request.getName(), request.getDescription(),
                request.getMode() != null ? SquadMode.valueOf(request.getMode()) : null,
                null, phases, request.getActive());
        squadRepository.update(squad);
        return toResponse(squad);
    }

    /** 删除 Squad 及关联的执行记录、契约、审核记录。 */
    @Transactional
    public void delete(UUID accountId, UUID squadId) {
        Squad squad = findOwned(accountId, squadId);
        List<SquadExecution> executions = squadExecutionRepository.findBySquadId(squadId);
        for (SquadExecution execution : executions) {
            contractRepository.deleteByExecutionId(execution.getExecutionId());
            hitlReviewRepository.deleteByExecutionId(execution.getExecutionId());
        }
        squadExecutionRepository.deleteBySquadId(squadId);
        squadRepository.delete(squad.getSquadId());
    }

    /** 批量删除 Squad（含级联）。 */
    @Transactional
    public void deleteBatch(UUID accountId, java.util.List<UUID> ids) {
        if (ids == null || ids.isEmpty()) return;
        for (UUID id : ids) {
            delete(accountId, id);
        }
    }

    /** 删除单条执行记录及其关联的契约和审核记录。 */
    @Transactional
    public void deleteExecution(UUID accountId, UUID executionId) {
        SquadExecution execution = findExecutionOwned(accountId, executionId);
        contractRepository.deleteByExecutionId(executionId);
        hitlReviewRepository.deleteByExecutionId(executionId);
        squadExecutionRepository.delete(executionId);
    }

    /** 批量删除执行记录。 */
    @Transactional
    public void deleteExecutionsBatch(java.util.List<UUID> ids, UUID accountId) {
        if (ids == null || ids.isEmpty()) return;
        for (UUID id : ids) {
            // 校验执行归属
            findExecutionOwned(accountId, id);
            contractRepository.deleteByExecutionId(id);
            hitlReviewRepository.deleteByExecutionId(id);
            pipelineStepRepository.deleteByExecutionId(id);
        }
        squadExecutionRepository.deleteBatch(ids);
    }

    /** 查询 Squad 的执行记录列表。 */
    public List<SquadExecutionResponse> getExecutions(UUID squadId) {
        List<SquadExecution> executions = squadExecutionRepository.findBySquadId(squadId);
        return executions.stream().map(this::toExecutionResponse).toList();
    }

    /** 分页查询 Squad 的执行记录列表。 */
    public PageResponse<SquadExecutionResponse> getExecutionsPage(UUID accountId, UUID squadId, int page, int size) {
        findOwned(accountId, squadId);
        List<SquadExecutionResponse> items = squadExecutionRepository.findBySquadIdPage(squadId, page, size)
                .stream().map(this::toExecutionResponse).toList();
        long total = squadExecutionRepository.countBySquadId(squadId);
        return PageResponse.of(items, total, page + 1, size);
    }

    /** 查询 Squad 执行详情。 */
    public SquadExecutionResponse getExecution(UUID accountId, UUID executionId) {
        return toExecutionResponse(findExecutionOwned(accountId, executionId));
    }

    /** 获取执行领域实体（供 HITL 等内部使用）。 */
    public SquadExecution findExecutionOwned(UUID accountId, UUID executionId) {
        SquadExecution execution = squadExecutionRepository.findById(executionId)
                .orElseThrow(() -> ResourceNotFoundException.notFound("执行记录", executionId));
        Squad squad = squadRepository.findById(execution.getSquadId())
                .orElseThrow(() -> ResourceNotFoundException.notFound("Squad", execution.getSquadId()));
        if (!squad.getAccountId().equals(accountId)) {
            throw new ResourceNotFoundException("执行记录", executionId);
        }
        return execution;
    }

    /** 查询执行中的契约列表。 */
    public List<ContractResponse> getContracts(UUID executionId, UUID accountId) {
        return contractService.getByExecution(executionId, accountId);
    }

    /** 触发 Squad 异步执行，立即返回 executionId。 */
    public SquadExecutionResponse execute(UUID accountId, UUID squadId, ExecuteSquadRequest request) {
        Squad squad = findOwned(accountId, squadId);

        SquadExecution execution = SquadExecution.builder()
                .squadId(squadId)
                .accountId(accountId)
                .projectId(squad.getProjectId())
                .taskDescription(request.getTaskDescription())
                .status(ExecutionStatus.PENDING)
                .topologySnapshot(buildTopologySnapshot(squad))
                .startedAt(LocalDateTime.now())
                .build();
        squadExecutionRepository.save(execution);

        executionEngine.execute(squad, execution, accountId);
        return toExecutionResponse(execution);
    }

    /** 订阅 Squad 执行进度事件（SSE），含所有权校验。 */
    public Flux<SquadExecutionEvent> streamEvents(UUID accountId, UUID executionId) {
        findExecutionOwned(accountId, executionId);
        return executionEngine.events(executionId);
    }

    /**
     * 手动覆盖契约内容（调试/错误修复后用于注入修正值并重试）。
     */
    public ContractResponse overrideContract(UUID accountId, UUID executionId, UUID contractId, String newContent) {
        findExecutionOwned(accountId, executionId);
        if (newContent == null || newContent.isBlank()) {
            throw new IllegalArgumentException("契约内容不能为空");
        }
        contractRepository.updateContent(contractId, newContent);
        var c = contractRepository.findById(contractId)
                .orElseThrow(() -> ResourceNotFoundException.notFound("契约", contractId));
        return contractService.toResponse(c);
    }

    /**
     * 分页查询当前用户所有执行记录（跨 Squad），支持按状态和 Squad 过滤。
     */
    public PageResponse<SquadExecutionResponse> listAllExecutions(UUID accountId, int page, int size, String status, UUID squadId) {
        List<SquadExecution> executions = squadExecutionRepository.findByAccountPage(accountId, page, size, status, squadId);
        List<SquadExecutionResponse> items = executions.stream().map(this::toExecutionResponse).toList();
        long total = status != null
                ? squadExecutionRepository.countByAccountAndStatus(accountId, status)
                : squadExecutionRepository.countByAccount(accountId);
        return PageResponse.of(items, total, page + 1, size);
    }

    /**
     * 获取当前用户的执行统计：运行中 / 等待 HITL / 今日完成 / 今日失败。
     */
    public ExecutionStatsResponse getExecutionStats(UUID accountId) {
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        return ExecutionStatsResponse.builder()
                .running(squadExecutionRepository.countByAccountAndStatus(accountId, "RUNNING"))
                .hitlWaiting(squadExecutionRepository.countByAccountAndStatus(accountId, "HITL_PENDING"))
                .todayDone(squadExecutionRepository.countByAccountAndStatusSince(accountId, "COMPLETED", todayStart))
                .todayFailed(squadExecutionRepository.countByAccountAndStatusSince(accountId, "FAILED", todayStart))
                .build();
    }

    /** 仪表盘 SSE 流：初始全量 + 3s 定时统计 + 执行状态变更实时推送。 */
    public Flux<DashboardEvent> createDashboardStream(UUID accountId) {
        Flux<DashboardEvent> periodic = Flux.interval(Duration.ofSeconds(3))
                .map(i -> buildDashboardSnapshot(accountId));

        Flux<DashboardEvent> reactive = executionEngine.allExecutionEvents()
                .sample(Duration.ofMillis(500))
                .flatMap(event -> Mono.fromCallable(() -> buildDashboardSnapshot(accountId))
                        .subscribeOn(Schedulers.boundedElastic()));

        return Flux.just(buildDashboardSnapshot(accountId))
                .concatWith(Flux.merge(periodic, reactive));
    }

    private DashboardEvent buildDashboardSnapshot(UUID accountId) {
        var stats = buildStats(accountId);
        // use first page as snapshot (page=0, size=50)
        List<SquadExecution> executions = squadExecutionRepository.findByAccountPage(accountId, 0, 50, null, null);
        List<SquadExecutionResponse> items = executions.stream().map(this::toExecutionResponse).toList();
        long total = squadExecutionRepository.countByAccount(accountId);
        return DashboardEvent.builder().stats(stats).executions(items).totalCount(total).build();
    }

    private ExecutionStatsResponse buildStats(UUID accountId) {
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        return ExecutionStatsResponse.builder()
                .running(squadExecutionRepository.countByAccountAndStatus(accountId, "RUNNING"))
                .hitlWaiting(squadExecutionRepository.countByAccountAndStatus(accountId, "HITL_PENDING"))
                .todayDone(squadExecutionRepository.countByAccountAndStatusSince(accountId, "COMPLETED", todayStart))
                .todayFailed(squadExecutionRepository.countByAccountAndStatusSince(accountId, "FAILED", todayStart))
                .build();
    }

    private String buildTopologySnapshot(Squad squad) {
        if (squad.getPhases() == null) return "[]";
        return squad.getPhases().stream()
                .map(p -> {
                    StringBuilder sb = new StringBuilder("{");
                    sb.append("\"phase\":").append(p.getPhase());
                    if (p.getName() != null) {
                        sb.append(",\"name\":\"").append(escapeJson(p.getName())).append("\"");
                    }
                    sb.append(",\"agents\":[");
                    var agents = p.getAgents();
                    if (agents != null) {
                        for (int i = 0; i < agents.size(); i++) {
                            if (i > 0) sb.append(",");
                            sb.append("\"").append(escapeJson(agents.get(i))).append("\"");
                        }
                    }
                    sb.append("]");
                    if (p.getHitlMode() != null) {
                        sb.append(",\"hitlMode\":\"").append(escapeJson(p.getHitlMode())).append("\"");
                    }
                    if (p.getHitlAgents() != null && !p.getHitlAgents().isEmpty()) {
                        sb.append(",\"hitlAgents\":[");
                        for (int i = 0; i < p.getHitlAgents().size(); i++) {
                            if (i > 0) sb.append(",");
                            sb.append("\"").append(escapeJson(p.getHitlAgents().get(i))).append("\"");
                        }
                        sb.append("]");
                    }
                    sb.append("}");
                    return sb.toString();
                })
                .collect(Collectors.joining(",", "[", "]"));
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** 查找当前用户拥有的 Squad。 */
    private Squad findOwned(UUID accountId, UUID squadId) {
        return OwnershipValidator.findOwned(accountId, "Squad", squadId,
                squadRepository::findById, Squad::getAccountId);
    }

    /** 转换为响应对象。 */
    private SquadResponse toResponse(Squad squad) {
        List<PhaseNodeResponse> phaseResponses = squad.getPhases() != null
                ? squad.getPhases().stream().map(p -> PhaseNodeResponse.builder()
                .phase(p.getPhase())
                .name(p.getName())
                .mode(p.getMode() != null ? p.getMode().name() : null)
                .agents(p.getAgents())
                .description(p.getDescription())
                .inputFilter(p.getInputFilter())
                .outputFilter(p.getOutputFilter())
                .hitlMode(p.getHitlMode())
                .hitlAgents(p.getHitlAgents())
                .build()).toList()
                : List.of();

        return SquadResponse.builder()
                .squadId(squad.getSquadId())
                .projectId(squad.getProjectId())
                .name(squad.getName())
                .description(squad.getDescription())
                .mode(squad.getMode() != null ? squad.getMode().name() : null)
                .source(squad.getSource() != null ? squad.getSource().name() : null)
                .active(squad.getActive())
                .phases(phaseResponses)
                .usageCount(squad.getUsageCount())
                .successRate(squad.getSuccessRate())
                .createdAt(squad.getCreatedAt())
                .updatedAt(squad.getUpdatedAt())
                .build();
    }

    private SquadExecutionResponse toExecutionResponse(SquadExecution execution) {
        String mode = null;
        String squadName = null;
        try {
            var squad = squadRepository.findById(execution.getSquadId());
            if (squad.isPresent()) {
                mode = squad.get().getMode() != null ? squad.get().getMode().name() : null;
                squadName = squad.get().getName();
            }
        } catch (Exception e) {
            log.warn("查询 Squad 信息失败: executionId={}", execution.getExecutionId(), e);
        }
        return SquadExecutionResponse.builder()
                .executionId(execution.getExecutionId())
                .squadId(execution.getSquadId())
                .projectId(execution.getProjectId())
                .taskDescription(execution.getTaskDescription())
                .status(execution.getStatus() != null ? execution.getStatus().name() : null)
                .content(execution.getContent())
                .thinking(execution.getThinking())
                .topologySnapshot(execution.getTopologySnapshot())
                .squadName(squadName)
                .squadMode(mode)
                .currentPhase(execution.getCurrentPhase())
                .errorMessage(execution.getErrorMessage())
                .startedAt(execution.getStartedAt())
                .pausedAt(execution.getPausedAt())
                .completedAt(execution.getCompletedAt())
                .createdAt(execution.getCreatedAt())
                .build();
    }

    /**
     * 防御性 agentId → agentName 转换。前端可能传 UUID，这里通过 agent
     * 仓库反查名称，确保 PhaseNode.agents 始终存储 agentName。
     */
    private List<String> resolveAgentNames(List<String> agents, UUID accountId) {
        if (agents == null || agents.isEmpty()) return List.of();
        return agents.stream()
                .map(a -> resolveSingleAgentName(a, accountId))
                .toList();
    }

    private String resolveSingleAgentName(String agentIdentifier, UUID accountId) {
        try {
            UUID agentId = UUID.fromString(agentIdentifier);
            return agentRepository.findById(agentId)
                    .map(agent -> agent.getAgentName())
                    .orElse(agentIdentifier);
        } catch (IllegalArgumentException e) {
            return agentIdentifier;
        }
    }
}
