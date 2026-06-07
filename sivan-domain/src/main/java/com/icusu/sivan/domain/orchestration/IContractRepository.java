package com.icusu.sivan.domain.orchestration;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 契约仓储接口。
 */
public interface IContractRepository {

    /** 保存契约。 */
    UUID save(Contract contract);

    /** 根据 ID 查找契约。 */
    Optional<Contract> findById(UUID contractId);

    /** 根据执行 ID 查找契约列表。 */
    List<Contract> findByExecutionId(UUID executionId);

    /** 根据执行 ID 和阶段查找契约列表（用于重试时获取前一阶段输出）。 */
    List<Contract> findByExecutionIdAndPhase(UUID executionId, int phase);

    /** 根据多个执行 ID 查找契约列表。 */
    List<Contract> findByExecutionIdIn(List<UUID> executionIds);

    /** 删除执行相关的所有契约。 */
    void deleteByExecutionId(UUID executionId);

    /** 更新契约内容（用于手动注入修正值）。 */
    void updateContent(UUID contractId, String newContent);
}
