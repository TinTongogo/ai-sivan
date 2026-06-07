package com.icusu.sivan.web.orchestration.service;

import com.icusu.sivan.common.exception.ResourceNotFoundException;
import com.icusu.sivan.common.util.OwnershipValidator;
import com.icusu.sivan.domain.orchestration.Contract;
import com.icusu.sivan.domain.orchestration.SquadExecution;
import com.icusu.sivan.domain.orchestration.IContractRepository;
import com.icusu.sivan.domain.orchestration.ISquadExecutionRepository;
import com.icusu.sivan.web.orchestration.dto.ContractResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/** 契约服务，管理智能体间消息契约。 */
@Service
@RequiredArgsConstructor
public class ContractService {

    private final IContractRepository contractRepository;
    private final ISquadExecutionRepository squadExecutionRepository;

    /** 根据执行 ID 查询契约列表。 */
    public List<ContractResponse> getByExecution(UUID executionId, UUID accountId) {
        // 校验执行所属 squad 的所有权
        squadExecutionRepository.findById(executionId)
                .filter(exec -> exec.getAccountId().equals(accountId))
                .orElseThrow(() -> new ResourceNotFoundException("执行记录不存在"));
        List<Contract> contracts = contractRepository.findByExecutionId(executionId);
        return contracts.stream().map(this::toResponse).toList();
    }

    /** 根据 Squad ID 查询契约列表。 */
    public List<ContractResponse> getBySquad(UUID accountId, UUID squadId) {
        List<SquadExecution> executions = squadExecutionRepository.findBySquadIdAndAccountId(squadId, accountId);

        if (executions.isEmpty()) {
            return List.of();
        }

        List<UUID> executionIds = executions.stream()
                .map(SquadExecution::getExecutionId)
                .toList();
        List<Contract> contracts = contractRepository.findByExecutionIdIn(executionIds);
        return contracts.stream().map(this::toResponse).toList();
    }

    /** 根据 ID 查询契约详情。 */
    public ContractResponse getById(UUID accountId, UUID contractId) {
        return toResponse(OwnershipValidator.findOwned(accountId, "契约", contractId,
                contractRepository::findById, Contract::getAccountId));
    }

    /** 转换为响应对象。 */
    public ContractResponse toResponse(Contract contract) {
        return ContractResponse.builder()
                .contractId(contract.getContractId())
                .executionId(contract.getExecutionId())
                .phase(contract.getPhase())
                .sourceAgent(contract.getSourceAgent())
                .targetAgent(contract.getTargetAgent())
                .content(contract.getContent())
                .contentType(contract.getContentType())
                .createdAt(contract.getCreatedAt())
                .build();
    }
}
