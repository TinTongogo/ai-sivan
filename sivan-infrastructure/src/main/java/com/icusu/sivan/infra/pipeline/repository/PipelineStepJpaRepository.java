package com.icusu.sivan.infra.pipeline.repository;

import com.icusu.sivan.infra.pipeline.entity.PipelineStepEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * pipeline_steps 表数据访问接口。
 */
@Repository
public interface PipelineStepJpaRepository extends JpaRepository<PipelineStepEntity, UUID> {

    List<PipelineStepEntity> findByMessageIdOrderBySequenceAsc(UUID messageId);

    List<PipelineStepEntity> findByParentStepIdOrderBySequenceAsc(UUID parentStepId);

    void deleteByMessageId(UUID messageId);

    void deleteByExecutionId(UUID executionId);
}
