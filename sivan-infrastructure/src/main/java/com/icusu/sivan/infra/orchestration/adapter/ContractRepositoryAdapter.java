package com.icusu.sivan.infra.orchestration.adapter;

import com.icusu.sivan.domain.orchestration.Contract;
import com.icusu.sivan.domain.orchestration.IContractRepository;
import com.icusu.sivan.infra.orchestration.entity.ContractEntity;
import com.icusu.sivan.infra.orchestration.repository.ContractJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 契约仓储适配器，实现 IContractRepository。
 */
@Component
@RequiredArgsConstructor
public class ContractRepositoryAdapter implements IContractRepository {

    private final ContractJpaRepository jpaRepository;

    /** 保存契约，回写 ID 和时间戳。 */
    @Override
    public UUID save(Contract contract) {
        ContractEntity entity = toEntity(contract);
        jpaRepository.save(entity);
        if (contract.getContractId() == null) {
            contract.setContractId(entity.getContractId());
        }
        contract.setCreatedAt(entity.getCreatedAt() != null ? entity.getCreatedAt().toLocalDateTime() : null);
        return entity.getContractId();
    }

    /** 根据 ID 查询契约。 */
    @Override
    public Optional<Contract> findById(UUID contractId) {
        return jpaRepository.findById(contractId).map(this::toDomain);
    }

    /** 根据执行 ID 查询契约列表。 */
    @Override
    public List<Contract> findByExecutionId(UUID executionId) {
        return jpaRepository.findByExecutionId(executionId).stream()
                .map(this::toDomain).toList();
    }

    /** 根据执行 ID 和阶段查询契约列表。 */
    @Override
    public List<Contract> findByExecutionIdAndPhase(UUID executionId, int phase) {
        return jpaRepository.findByExecutionIdAndPhase(executionId, phase).stream()
                .map(this::toDomain).toList();
    }

    /** 根据多个执行 ID 查询契约列表。 */
    @Override
    public List<Contract> findByExecutionIdIn(List<UUID> executionIds) {
        return jpaRepository.findByExecutionIdIn(executionIds).stream()
                .map(this::toDomain).toList();
    }

    /** 删除执行相关的所有契约。 */
    @Override
    public void deleteByExecutionId(UUID executionId) {
        jpaRepository.deleteByExecutionId(executionId);
    }

    /** 更新契约内容（用于手动注入修正值）。 */
    @Override
    public void updateContent(UUID contractId, String newContent) {
        jpaRepository.findById(contractId).ifPresent(entity -> {
            entity.setContent(newContent);
            jpaRepository.save(entity);
        });
    }

    // ---- 转换方法 ----

    /** 将实体转换为领域对象。 */
    private Contract toDomain(ContractEntity entity) {
        return Contract.builder()
                .contractId(entity.getContractId())
                .executionId(entity.getExecutionId())
                .accountId(entity.getAccountId())
                .projectId(entity.getProjectId())
                .phase(entity.getPhase())
                .sourceAgent(entity.getSourceAgent())
                .targetAgent(entity.getTargetAgent())
                .content(entity.getContent())
                .contentType(entity.getContentType())
                .createdAt(entity.getCreatedAt() != null ? entity.getCreatedAt().toLocalDateTime() : null)
                .build();
    }

    /** 将领域对象转换为实体。 */
    private ContractEntity toEntity(Contract contract) {
        ContractEntity entity = new ContractEntity();
        entity.setContractId(contract.getContractId());
        entity.setExecutionId(contract.getExecutionId());
        entity.setAccountId(contract.getAccountId());
        entity.setProjectId(contract.getProjectId());
        entity.setPhase(contract.getPhase());
        entity.setSourceAgent(contract.getSourceAgent());
        entity.setTargetAgent(contract.getTargetAgent());
        entity.setContent(contract.getContent());
        entity.setContentType(contract.getContentType() != null ? contract.getContentType() : "text");
        return entity;
    }
}
