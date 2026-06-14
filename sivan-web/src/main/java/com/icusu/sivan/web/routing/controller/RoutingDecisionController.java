package com.icusu.sivan.web.routing.controller;

import com.icusu.sivan.common.dto.BaseResponse;
import com.icusu.sivan.common.dto.PageResponse;
import com.icusu.sivan.application.routing.dto.CreateRoutingDecisionRequest;
import com.icusu.sivan.application.routing.dto.RoutingDecisionResponse;
import com.icusu.sivan.application.service.RoutingDecisionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import com.icusu.sivan.web.shared.security.CurrentAccountId;
import java.util.List;
import java.util.UUID;

/**
 * 路由决策管理控制器。
 */
@RestController
@RequestMapping("/api/routing-decisions")
@RequiredArgsConstructor
public class RoutingDecisionController {

    private final RoutingDecisionService routingDecisionService;

    /** 创建路由决策记录。 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BaseResponse<RoutingDecisionResponse> create(@Valid @RequestBody CreateRoutingDecisionRequest request, @CurrentAccountId UUID accountId) {
        return BaseResponse.created(routingDecisionService.create(accountId, request));
    }

    /** 根据 ID 获取路由决策。 */
    @GetMapping("/{decisionId}")
    public BaseResponse<RoutingDecisionResponse> getById(@PathVariable UUID decisionId,
                                                         @CurrentAccountId UUID accountId) {
        return BaseResponse.success(routingDecisionService.getById(decisionId, accountId));
    }

    /** 获取路由决策列表（分页），可按策略过滤。 */
    @GetMapping
    public BaseResponse<PageResponse<RoutingDecisionResponse>> list(
            @RequestParam(required = false) String strategy,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size, @CurrentAccountId UUID accountId) {
        return BaseResponse.success(routingDecisionService.listPage(accountId, page, size, strategy));
    }

    /** 删除路由决策。 */
    @DeleteMapping("/{decisionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public BaseResponse<Void> delete(@PathVariable UUID decisionId,
                                      @CurrentAccountId UUID accountId) {
        routingDecisionService.delete(decisionId, accountId);
        return BaseResponse.success();
    }

    /** 批量删除路由决策。 */
    @PostMapping("/batch-delete")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public BaseResponse<Void> deleteBatch(@RequestBody List<UUID> decisionIds,
                                           @CurrentAccountId UUID accountId) {
        routingDecisionService.deleteBatch(decisionIds, accountId);
        return BaseResponse.success();
    }

}
