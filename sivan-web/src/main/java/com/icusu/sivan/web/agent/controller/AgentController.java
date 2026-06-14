package com.icusu.sivan.web.agent.controller;

import com.icusu.sivan.common.dto.BaseResponse;
import com.icusu.sivan.application.agent.dto.AgentResponse;
import com.icusu.sivan.application.agent.dto.CreateAgentRequest;
import com.icusu.sivan.application.agent.dto.UpdateAgentRequest;
import com.icusu.sivan.application.service.AgentService;
import com.icusu.sivan.web.shared.security.CurrentAccountId;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 智能体 CRUD 控制器。
 */
@RestController
@RequestMapping("/api/agents")
@RequiredArgsConstructor
public class AgentController {

    private final AgentService agentService;

    /**
     * 创建智能体。
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BaseResponse<AgentResponse> create(@Valid @RequestBody CreateAgentRequest request, @CurrentAccountId UUID accountId) {
        return BaseResponse.created(agentService.create(accountId, request));
    }

    /**
     * 根据 ID 获取智能体。
     */
    @GetMapping("/{agentId}")
    public BaseResponse<AgentResponse> getById(@PathVariable UUID agentId, @CurrentAccountId UUID accountId) {
        return BaseResponse.success(agentService.getById(accountId, agentId));
    }

    /**
     * 获取智能体列表。
     */
    @GetMapping
    public BaseResponse<List<AgentResponse>> list(@CurrentAccountId UUID accountId) {
        return BaseResponse.success(agentService.list(accountId));
    }

    /**
     * 更新智能体信息。
     */
    @PutMapping("/{agentId}")
    public BaseResponse<AgentResponse> update(@PathVariable UUID agentId, @Valid @RequestBody UpdateAgentRequest request, @CurrentAccountId UUID accountId) {
        return BaseResponse.success(agentService.update(accountId, agentId, request));
    }

    /**
     * 删除智能体。
     */
    @DeleteMapping("/{agentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public BaseResponse<Void> delete(@PathVariable UUID agentId, @CurrentAccountId UUID accountId) {
        agentService.delete(accountId, agentId);
        return BaseResponse.success();
    }

    /**
     * 批量删除智能体。
     */
    @PostMapping("/batch-delete")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public BaseResponse<Void> deleteBatch(@RequestBody java.util.List<UUID> agentIds,
                                          @CurrentAccountId UUID accountId) {
        agentService.deleteBatch(agentIds, accountId);
        return BaseResponse.success();
    }

    /**
     * 获取智能体类型分布统计。
     */
    @GetMapping("/type-distribution")
    public BaseResponse<Map<String, Long>> getTypeDistribution(@CurrentAccountId UUID accountId) {
        return BaseResponse.success(agentService.getTypeDistribution(accountId));
    }
}
