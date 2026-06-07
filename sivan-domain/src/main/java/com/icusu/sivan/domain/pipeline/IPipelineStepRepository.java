package com.icusu.sivan.domain.pipeline;

import java.util.List;
import java.util.UUID;

/**
 * 编排流水线步骤仓储接口。
 */
public interface IPipelineStepRepository {

    /** 保存步骤。 */
    PipelineStep save(PipelineStep step);

    /** 批量保存步骤。 */
    List<PipelineStep> saveAll(List<PipelineStep> steps);

    /** 按消息 ID 查询步骤列表（按 sequence 排序）。 */
    List<PipelineStep> findByMessageId(UUID messageId);

    /** 按父步骤 ID 查询子步骤。 */
    List<PipelineStep> findByParentStepId(UUID parentStepId);

    /** 删除指定消息的所有步骤。 */
    void deleteByMessageId(UUID messageId);

    /** 删除指定执行 ID 的所有步骤。 */
    void deleteByExecutionId(UUID executionId);
}
