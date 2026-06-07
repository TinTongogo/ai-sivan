package com.icusu.sivan.infra.feedback.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.icusu.sivan.domain.feedback.FeatureDeviation;
import com.icusu.sivan.domain.feedback.IPatternFeedbackRepository;
import com.icusu.sivan.domain.feedback.PatternFeedbackRecord;
import com.icusu.sivan.domain.task.TaskFeatures;
import com.icusu.sivan.infra.feedback.entity.PatternFeedbackEntity;
import com.icusu.sivan.infra.feedback.repository.PatternFeedbackJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.TimeZone;
import java.util.UUID;

/**
 * 本能模板反馈仓储适配器，实现 IPatternFeedbackRepository。
 */
@Component
@RequiredArgsConstructor
public class PatternFeedbackRepositoryAdapter implements IPatternFeedbackRepository {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .setTimeZone(TimeZone.getTimeZone("UTC"));

    private final PatternFeedbackJpaRepository jpaRepository;

    @Override
    public void save(PatternFeedbackRecord record) {
        PatternFeedbackEntity entity = toEntity(record);
        jpaRepository.save(entity);
        if (record.getFeedbackId() == null) {
            record.setFeedbackId(entity.getFeedbackId());
        }
        record.setCreatedAt(entity.getCreatedAt() != null
                ? entity.getCreatedAt().toLocalDateTime() : null);
    }

    @Override
    public List<PatternFeedbackRecord> findByExecutionId(UUID executionId) {
        return jpaRepository.findByExecutionIdOrderByCreatedAtDesc(executionId).stream()
                .map(this::toDomain).toList();
    }

    @Override
    public List<PatternFeedbackRecord> findByPatternId(UUID patternId) {
        return jpaRepository.findByPatternIdOrderByCreatedAtDesc(patternId).stream()
                .map(this::toDomain).toList();
    }

    @Override
    public List<PatternFeedbackRecord> findByCreatedAtBefore(LocalDateTime cutoff) {
        OffsetDateTime cutoffUtc = cutoff.atOffset(ZoneOffset.UTC);
        return jpaRepository.findByCreatedAtBefore(cutoffUtc).stream()
                .map(this::toDomain).toList();
    }

    @Override
    public void deleteByFeedbackId(UUID feedbackId) {
        jpaRepository.deleteById(feedbackId);
    }

    // ---- 转换方法 ----

    private PatternFeedbackRecord toDomain(PatternFeedbackEntity entity) {
        return PatternFeedbackRecord.builder()
                .feedbackId(entity.getFeedbackId())
                .patternId(entity.getPatternId())
                .accountId(entity.getAccountId())
                .executionId(entity.getExecutionId())
                .actualFeatures(fromJson(entity.getActualFeaturesJson(), TaskFeatures.class))
                .taskDescription(entity.getTaskDescription())
                .outcome(PatternFeedbackRecord.FeedbackOutcome.valueOf(entity.getOutcome()))
                .outcomeReason(entity.getOutcomeReason())
                .tokenCost(entity.getTokenCost())
                .deviation(fromJson(entity.getDeviationJson(), FeatureDeviation.class))
                .source(entity.getSource())
                .createdAt(entity.getCreatedAt() != null
                        ? entity.getCreatedAt().toLocalDateTime() : null)
                .build();
    }

    private PatternFeedbackEntity toEntity(PatternFeedbackRecord record) {
        PatternFeedbackEntity entity = new PatternFeedbackEntity();
        entity.setFeedbackId(record.getFeedbackId());
        entity.setPatternId(record.getPatternId());
        entity.setAccountId(record.getAccountId());
        entity.setExecutionId(record.getExecutionId());
        entity.setActualFeaturesJson(toJson(record.getActualFeatures()));
        entity.setTaskDescription(record.getTaskDescription());
        entity.setOutcome(record.getOutcome().name());
        entity.setOutcomeReason(record.getOutcomeReason());
        entity.setTokenCost(record.getTokenCost());
        entity.setDeviationJson(toJson(record.getDeviation()));
        entity.setSource(record.getSource());
        return entity;
    }

    private static <T> T fromJson(String json, Class<T> type) {
        if (json == null || json.isBlank()) return null;
        try {
            return OBJECT_MAPPER.readValue(json, type);
        } catch (Exception e) {
            return null;
        }
    }

    private static String toJson(Object obj) {
        if (obj == null) return null;
        try {
            return OBJECT_MAPPER.writeValueAsString(obj);
        } catch (Exception e) {
            return null;
        }
    }
}
