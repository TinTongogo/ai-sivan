package com.icusu.sivan.web.orchestration.controller;

import com.icusu.sivan.common.dto.BaseResponse;
import com.icusu.sivan.web.orchestration.dto.ContractResponse;
import com.icusu.sivan.web.orchestration.service.ContractService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.icusu.sivan.web.shared.security.CurrentAccountId;
import java.util.List;
import java.util.UUID;

/**
 * 契约查询控制器。
 */
@RestController
@RequestMapping("/api/contracts")
@RequiredArgsConstructor
public class ContractController {

    private final ContractService contractService;

    /** 根据 Squad ID 获取契约列表。 */
    @GetMapping("/by-squad/{squadId}")
    public BaseResponse<List<ContractResponse>> getBySquad(@PathVariable UUID squadId, @CurrentAccountId UUID accountId) {
                return BaseResponse.success(contractService.getBySquad(accountId, squadId));
    }

    /** 根据执行 ID 获取契约列表。 */
    @GetMapping("/by-execution/{executionId}")
    public BaseResponse<List<ContractResponse>> getByExecution(@PathVariable UUID executionId,
                                                                @CurrentAccountId UUID accountId) {
        return BaseResponse.success(contractService.getByExecution(executionId, accountId));
    }

    /** 根据 ID 获取契约详情。 */
    @GetMapping("/{contractId}")
    public BaseResponse<ContractResponse> getById(@PathVariable UUID contractId, @CurrentAccountId UUID accountId) {
                return BaseResponse.success(contractService.getById(accountId, contractId));
    }
}
