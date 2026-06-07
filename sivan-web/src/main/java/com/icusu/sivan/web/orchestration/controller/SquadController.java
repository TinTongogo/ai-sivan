package com.icusu.sivan.web.orchestration.controller;

import com.icusu.sivan.common.dto.BaseResponse;
import com.icusu.sivan.common.dto.PageResponse;
import com.icusu.sivan.domain.orchestration.HitlReview;
import com.icusu.sivan.orch.executor.SquadExecutionEngine;
import com.icusu.sivan.orch.executor.SquadExecutionEvent;
import com.icusu.sivan.orch.hitl.HitlService;
import com.icusu.sivan.orch.topology.TopologyResult;
import com.icusu.sivan.web.orchestration.dto.ContractResponse;
import com.icusu.sivan.web.orchestration.dto.CreateSquadRequest;
import com.icusu.sivan.web.orchestration.dto.DashboardEvent;
import com.icusu.sivan.web.orchestration.dto.ExecuteSquadRequest;
import com.icusu.sivan.web.orchestration.dto.ExecutionStatsResponse;
import com.icusu.sivan.web.orchestration.dto.GenerateTopologyRequest;
import com.icusu.sivan.web.orchestration.dto.HitlActionRequest;
import com.icusu.sivan.web.orchestration.dto.HitlReviewResponse;
import com.icusu.sivan.web.orchestration.dto.SquadExecutionResponse;
import com.icusu.sivan.web.orchestration.dto.SquadResponse;
import com.icusu.sivan.web.orchestration.dto.UpdateSquadRequest;
import com.icusu.sivan.web.orchestration.service.SquadService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import com.icusu.sivan.web.shared.security.CurrentAccountId;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Squad（智能体编队）管理控制器。
 */
@RestController
@RequestMapping("/api/squads")
@RequiredArgsConstructor
public class SquadController {

    private final SquadService squadService;
    private final HitlService hitlService;
    private final SquadExecutionEngine executionEngine;

    /**
     * 创建 Squad。
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BaseResponse<SquadResponse> create(@Valid @RequestBody CreateSquadRequest request, @CurrentAccountId UUID accountId) {
                return BaseResponse.created(squadService.create(accountId, request));
    }

    /**
     * 根据 ID 获取 Squad。
     */
    @GetMapping("/{squadId}")
    public BaseResponse<SquadResponse> getById(@PathVariable UUID squadId, @CurrentAccountId UUID accountId) {
                return BaseResponse.success(squadService.getById(accountId, squadId));
    }

    /**
     * 获取 Squad 列表（分页），可按项目 ID 和来源过滤。
     */
    @GetMapping
    public BaseResponse<PageResponse<SquadResponse>> list(
            @RequestParam(required = false) UUID projectId,
            @RequestParam(required = false) String source,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size, @CurrentAccountId UUID accountId) {
                return BaseResponse.success(squadService.listPage(accountId, page, size, projectId, source));
    }

    /**
     * 更新 Squad 信息。
     */
    @PutMapping("/{squadId}")
    public BaseResponse<SquadResponse> update(@PathVariable UUID squadId,
                                              @Valid @RequestBody UpdateSquadRequest request, @CurrentAccountId UUID accountId) {
                return BaseResponse.success(squadService.update(accountId, squadId, request));
    }

    /**
     * 删除 Squad。
     */
    @DeleteMapping("/{squadId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public BaseResponse<Void> delete(@PathVariable UUID squadId, @CurrentAccountId UUID accountId) {
                squadService.delete(accountId, squadId);
        return BaseResponse.success();
    }

    /** 批量删除 Squad。 */
    @PostMapping("/batch-delete")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public BaseResponse<Void> deleteBatch(@RequestBody java.util.List<UUID> squadIds, @CurrentAccountId UUID accountId) {
                squadService.deleteBatch(accountId, squadIds);
        return BaseResponse.success();
    }

    /**
     * 生成 Squad 拓扑结构（LLM 调用为阻塞操作，须切换到 boundedElastic 线程池）。
     */
    @PostMapping("/generate-topology")
    public Mono<BaseResponse<TopologyResult>> generateTopology(@Valid @RequestBody GenerateTopologyRequest request, @CurrentAccountId UUID accountId) {
        return squadService.generateTopology(accountId, request)
                .map(BaseResponse::success);
    }

    /**
     * 执行 Squad 任务。
     */
    @PostMapping("/{squadId}/execute")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public BaseResponse<SquadExecutionResponse> execute(@PathVariable UUID squadId,
                                                        @Valid @RequestBody ExecuteSquadRequest request, @CurrentAccountId UUID accountId) {
                return BaseResponse.accepted(squadService.execute(accountId, squadId, request));
    }

    /**
     * 全局执行列表（跨 Squad），支持按状态和 Squad 过滤。
     */
    @GetMapping("/executions")
    public BaseResponse<PageResponse<SquadExecutionResponse>> listAllExecutions(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID squadId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size, @CurrentAccountId UUID accountId) {
                return BaseResponse.success(squadService.listAllExecutions(accountId, page, size, status, squadId));
    }

    /**
     * 执行统计：运行中 / 等待 HITL / 今日完成 / 今日失败。
     */
    @GetMapping("/executions/stats")
    public BaseResponse<ExecutionStatsResponse> getExecutionStats(@CurrentAccountId UUID accountId) {
                return BaseResponse.success(squadService.getExecutionStats(accountId));
    }

    /** 仪表盘 SSE 流：统计数据 + 执行列表实时推送。 */
    @GetMapping(value = "/executions/dashboard-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<DashboardEvent> dashboardStream(@CurrentAccountId UUID accountId) {
                return squadService.createDashboardStream(accountId);
    }

    /**
     * 流式获取 Squad 执行进度事件。
     */
    @GetMapping(value = "/executions/{executionId}/progress", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<SquadExecutionEvent> streamProgress(@PathVariable UUID executionId, @CurrentAccountId UUID accountId) {
                return squadService.streamEvents(accountId, executionId);
    }

    /**
     * 获取 Squad 执行记录列表（分页）。
     */
    @GetMapping("/{squadId}/executions")
    public BaseResponse<PageResponse<SquadExecutionResponse>> getExecutions(
            @PathVariable UUID squadId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size, @CurrentAccountId UUID accountId) {
                return BaseResponse.success(squadService.getExecutionsPage(accountId, squadId, page, size));
    }

    /**
     * 获取单次执行详情。
     */
    @GetMapping("/executions/{executionId}")
    public BaseResponse<SquadExecutionResponse> getExecution(@PathVariable UUID executionId, @CurrentAccountId UUID accountId) {
                return BaseResponse.success(squadService.getExecution(accountId, executionId));
    }

    /**
     * 获取执行生成的契约列表。
     */
    @GetMapping("/executions/{executionId}/contracts")
    public BaseResponse<List<ContractResponse>> getContracts(@PathVariable UUID executionId,
                                                             @CurrentAccountId UUID accountId) {
        squadService.findExecutionOwned(accountId, executionId);
        return BaseResponse.success(squadService.getContracts(executionId, accountId));
    }

    /**
     * 批准 HITL 审核，继续执行。
     */
    @PostMapping("/executions/{executionId}/hitl/{reviewId}/approve")
    public BaseResponse<HitlReview> approveHitl(@PathVariable UUID executionId,
                                                @PathVariable UUID reviewId,
                                                @RequestBody(required = false) HitlActionRequest request,
                                                @CurrentAccountId UUID accountId) {
        // 先校验执行归属，再操作
        var execution = squadService.findExecutionOwned(accountId, executionId);
        HitlReview review = hitlService.approve(reviewId, request != null ? request.getFeedback() : null);
        executionEngine.resume(execution, accountId);
        return BaseResponse.success(review);
    }

    /**
     * 驳回 HITL 审核，标记执行为失败。
     */
    @PostMapping("/executions/{executionId}/hitl/{reviewId}/reject")
    public BaseResponse<HitlReview> rejectHitl(@PathVariable UUID executionId,
                                               @PathVariable UUID reviewId,
                                               @RequestBody(required = false) HitlActionRequest request,
                                               @CurrentAccountId UUID accountId) {
        // 先校验执行归属，再操作
        squadService.findExecutionOwned(accountId, executionId);
        HitlReview review = hitlService.reject(reviewId, request != null ? request.getFeedback() : null);
        return BaseResponse.success(review);
    }

    /**
     * 恢复被 HITL 暂定的执行。
     */
    @PostMapping("/executions/{executionId}/resume")
    public BaseResponse<Void> resumeExecution(@PathVariable UUID executionId, @CurrentAccountId UUID accountId) {
                var execution = squadService.findExecutionOwned(accountId, executionId);
        executionEngine.resume(execution, accountId);
        return BaseResponse.success();
    }

    /**
     * 重试失败的执行阶段。从 currentPhase 重新开始，不浪费已完成的阶段。
     */
    @PostMapping("/executions/{executionId}/retry")
    public BaseResponse<Void> retryExecution(@PathVariable UUID executionId, @CurrentAccountId UUID accountId) {
                var execution = squadService.findExecutionOwned(accountId, executionId);
        executionEngine.retryPhase(execution, accountId);
        return BaseResponse.success();
    }

    /**
     * 删除单条执行记录及关联的契约、审核记录。
     */
    @DeleteMapping("/executions/{executionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public BaseResponse<Void> deleteExecution(@PathVariable UUID executionId, @CurrentAccountId UUID accountId) {
                squadService.deleteExecution(accountId, executionId);
        return BaseResponse.success();
    }

    /**
     * 批量删除执行记录。
     */
    @PostMapping("/executions/batch-delete")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public BaseResponse<Void> deleteExecutionsBatch(@RequestBody java.util.List<UUID> executionIds,
                                                    @CurrentAccountId UUID accountId) {
        squadService.deleteExecutionsBatch(executionIds, accountId);
        return BaseResponse.success();
    }

    /**
     * 获取当前待审核的 HITL 记录。
     */
    @GetMapping("/executions/{executionId}/hitl/review")
    public BaseResponse<HitlReviewResponse> getCurrentHitlReview(@PathVariable UUID executionId,
                                                                  @CurrentAccountId UUID accountId) {
        squadService.findExecutionOwned(accountId, executionId);
        return hitlService.getCurrentReview(executionId)
                .map(this::toHitlReviewResponse)
                .map(BaseResponse::success)
                .orElse(BaseResponse.success(null));
    }

    /**
     * 延长 HITL 审核等待时间（前端心跳，用户在审核弹窗中时调用）。
     */
    @PostMapping("/executions/{executionId}/hitl/{reviewId}/extend")
    public BaseResponse<HitlReviewResponse> extendHitlTimeout(@PathVariable UUID executionId,
                                                              @PathVariable UUID reviewId,
                                                              @CurrentAccountId UUID accountId) {
        // 校验执行归属后再延长
        squadService.findExecutionOwned(accountId, executionId);
        return BaseResponse.success(toHitlReviewResponse(hitlService.extendTimeout(reviewId)));
    }

    /**
     * 手动覆盖契约内容（用于调试/错误修复后重试）。
     */
    @PostMapping("/executions/{executionId}/contracts/{contractId}/override")
    public BaseResponse<ContractResponse> overrideContract(@PathVariable UUID executionId,
                                                           @PathVariable UUID contractId,
                                                           @RequestBody Map<String, String> body, @CurrentAccountId UUID accountId) {
                return BaseResponse.success(squadService.overrideContract(accountId, executionId, contractId, body.get("content")));
    }

    private HitlReviewResponse toHitlReviewResponse(HitlReview review) {
        return HitlReviewResponse.builder()
                .reviewId(review.getReviewId())
                .executionId(review.getExecutionId())
                .phase(review.getPhase())
                .phaseName(review.getPhaseName())
                .inputContent(review.getInputContent())
                .outputContent(review.getOutputContent())
                .humanFeedback(review.getHumanFeedback())
                .status(review.getStatus())
                .expiresAt(review.getExpiresAt())
                .createdAt(review.getCreatedAt())
                .updatedAt(review.getUpdatedAt())
                .build();
    }
}
