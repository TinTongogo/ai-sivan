package com.icusu.sivan.orch.contract;

import com.icusu.sivan.domain.orchestration.Contract;

import java.util.UUID;

/**
 * Contract 领域工厂。统一契约创建入口，强制不变量校验。
 */
public final class ContractFactory {

    private ContractFactory() {}

    /** 创建智能体间通信契约。 */
    public static Contract create(UUID executionId, UUID accountId, UUID projectId, int phase,
                                   String sourceAgent, String targetAgent, String content) {
        Contract contract = Contract.builder()
                .executionId(executionId)
                .accountId(accountId)
                .projectId(projectId)
                .phase(phase)
                .sourceAgent(sourceAgent)
                .targetAgent(targetAgent)
                .content(content)
                .contentType(Contract.inferContentType(content))
                .build();
        contract.validateInvariants();
        return contract;
    }
}
