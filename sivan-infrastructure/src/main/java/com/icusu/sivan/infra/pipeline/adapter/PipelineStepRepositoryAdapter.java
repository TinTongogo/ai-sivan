package com.icusu.sivan.infra.pipeline.adapter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.icusu.sivan.domain.pipeline.IPipelineStepRepository;
import com.icusu.sivan.domain.pipeline.PipelineStep;
import com.icusu.sivan.infra.pipeline.entity.PipelineStepEntity;
import com.icusu.sivan.infra.pipeline.repository.PipelineStepJpaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 编排流水线步骤仓储适配器。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PipelineStepRepositoryAdapter implements IPipelineStepRepository {

    private final PipelineStepJpaRepository jpaRepository;
    private final ObjectMapper objectMapper;

    @Override
    public PipelineStep save(PipelineStep step) {
        PipelineStepEntity entity = toEntity(step);
        jpaRepository.saveAndFlush(entity);
        step.setStepId(entity.getStepId());
        step.setCreatedAt(entity.getCreatedAt());
        return step;
    }

    @Override
    public List<PipelineStep> saveAll(List<PipelineStep> steps) {
        List<PipelineStepEntity> entities = steps.stream().map(this::toEntity).toList();
        List<PipelineStepEntity> saved = jpaRepository.saveAll(entities);
        for (int i = 0; i < steps.size(); i++) {
            steps.get(i).setStepId(saved.get(i).getStepId());
            steps.get(i).setCreatedAt(saved.get(i).getCreatedAt());
        }
        return steps;
    }

    @Override
    public List<PipelineStep> findByMessageId(UUID messageId) {
        return jpaRepository.findByMessageIdOrderBySequenceAsc(messageId).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public List<PipelineStep> findByParentStepId(UUID parentStepId) {
        return jpaRepository.findByParentStepIdOrderBySequenceAsc(parentStepId).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public void deleteByMessageId(UUID messageId) {
        jpaRepository.deleteByMessageId(messageId);
    }

    @Override
    public void deleteByExecutionId(UUID executionId) {
        jpaRepository.deleteByExecutionId(executionId);
    }

    // ---- 转换 ----

    private PipelineStep toDomain(PipelineStepEntity e) {
        Map<String, Object> meta = null;
        if (e.getMetadataJson() != null) {
            try {
                meta = objectMapper.readValue(e.getMetadataJson(), Map.class);
            } catch (JsonProcessingException ex) {
                log.warn("解析 pipeline_step metadata_json 失败: stepId={}", e.getStepId(), ex);
            }
        }
        return PipelineStep.builder()
                .stepId(e.getStepId())
                .messageId(e.getMessageId())
                .routingDecisionId(e.getRoutingDecisionId())
                .executionId(e.getExecutionId())
                .stepType(e.getStepType())
                .stepName(e.getStepName())
                .status(e.getStatus())
                .sequence(e.getSequence())
                .parentStepId(e.getParentStepId())
                .startedAt(e.getStartedAt())
                .completedAt(e.getCompletedAt())
                .durationMs(e.getDurationMs())
                .inputSummary(e.getInputSummary())
                .outputSummary(e.getOutputSummary())
                .agentName(e.getAgentName())
                .modelName(e.getModelName())
                .tokenCount(e.getTokenCount())
                .metadataJson(meta)
                .createdAt(e.getCreatedAt())
                .build();
    }

    private PipelineStepEntity toEntity(PipelineStep s) {
        String meta = null;
        if (s.getMetadataJson() != null) {
            try {
                meta = objectMapper.writeValueAsString(s.getMetadataJson());
            } catch (JsonProcessingException e) {
                log.warn("序列化 pipeline_step metadata_json 失败", e);
            }
        }
        return PipelineStepEntity.builder()
                .stepId(s.getStepId())
                .messageId(s.getMessageId())
                .routingDecisionId(s.getRoutingDecisionId())
                .executionId(s.getExecutionId())
                .stepType(s.getStepType())
                .stepName(s.getStepName())
                .status(s.getStatus())
                .sequence(s.getSequence())
                .parentStepId(s.getParentStepId())
                .startedAt(s.getStartedAt())
                .completedAt(s.getCompletedAt())
                .durationMs(s.getDurationMs())
                .inputSummary(s.getInputSummary())
                .outputSummary(s.getOutputSummary())
                .agentName(s.getAgentName())
                .modelName(s.getModelName())
                .tokenCount(s.getTokenCount())
                .metadataJson(meta)
                .build();
    }
}
