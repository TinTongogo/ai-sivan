package com.icusu.sivan.infra.memory.adapter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.icusu.sivan.domain.memory.InstinctPattern;
import com.icusu.sivan.domain.memory.IInstinctPatternRepository;
import com.icusu.sivan.domain.task.PatternFeatureVector;
import com.icusu.sivan.infra.memory.entity.InstinctPatternEntity;
import com.icusu.sivan.infra.memory.repository.InstinctPatternJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 本能模板仓储适配器，实现 IInstinctPatternRepository。
 */
@Component
@RequiredArgsConstructor
public class InstinctPatternRepositoryAdapter implements IInstinctPatternRepository {

    private final InstinctPatternJpaRepository jpaRepository;

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .build();

    /** 根据 ID 查询本能模板。 */
    @Override
    public Optional<InstinctPattern> findById(UUID patternId) {
        return jpaRepository.findById(patternId).map(this::toDomain);
    }

    /** 查询账号下已激活的本能模板。 */
    @Override
    public List<InstinctPattern> findActiveByAccount(UUID accountId) {
        return jpaRepository.findByAccountIdAndActiveTrue(accountId).stream()
                .map(this::toDomain).toList();
    }

    /** 查询指定时间后创建的本能模板（防抖用）。 */
    @Override
    public List<InstinctPattern> findByAccountIdAndCreatedAtAfter(UUID accountId, LocalDateTime after) {
        OffsetDateTime afterUtc = after.atOffset(ZoneOffset.UTC);
        return jpaRepository.findByAccountIdAndCreatedAtAfter(accountId, afterUtc).stream()
                .map(this::toDomain).toList();
    }

    /** 查询全部账号的活跃本能模板。 */
    @Override
    public List<InstinctPattern> findAllActive() {
        return jpaRepository.findByActiveTrue().stream()
                .map(this::toDomain).toList();
    }

    /** 保存本能模板，回写 ID 和时间戳。 */
    @Override
    public void save(InstinctPattern pattern) {
        InstinctPatternEntity entity = toEntity(pattern);
        jpaRepository.save(entity);
        if (pattern.getPatternId() == null) {
            pattern.setPatternId(entity.getPatternId());
        }
        pattern.setCreatedAt(entity.getCreatedAt() != null ? entity.getCreatedAt().toLocalDateTime() : null);
        pattern.setUpdatedAt(entity.getUpdatedAt() != null ? entity.getUpdatedAt().toLocalDateTime() : null);
    }

    /** 更新本能模板。 */
    @Override
    public void update(InstinctPattern pattern) {
        InstinctPatternEntity entity = jpaRepository.findById(pattern.getPatternId()).orElse(null);
        if (entity == null) {
            return;
        }
        entity.setTopologyJson(pattern.getTopologyJson());
        entity.setSuccessCount(pattern.getSuccessCount() != null ? pattern.getSuccessCount() : entity.getSuccessCount());
        entity.setTotalCount(pattern.getTotalCount() != null ? pattern.getTotalCount() : entity.getTotalCount());
        entity.setActive(pattern.getActive() != null ? pattern.getActive() : entity.getActive());
        entity.setFeatureVector(serializeFeatureVector(pattern.getFeatureVector()));
        entity.setExecutionMode(pattern.getExecutionMode());
        entity.setHitCount(pattern.getHitCount());
        entity.setSuccessRate(pattern.getSuccessRate());
        entity.setWeight(pattern.getWeight() != null ? pattern.getWeight() : 0.5);
        entity.setDraft(pattern.getDraft() != null ? pattern.getDraft() : false);
        entity.setVersion(pattern.getVersion());
        entity.setSourcePatternId(pattern.getSourcePatternId());
        entity.setLastMatchAt(pattern.getLastMatchAt() != null
                ? pattern.getLastMatchAt().atOffset(ZoneOffset.UTC) : null);
        jpaRepository.save(entity);
        pattern.setUpdatedAt(entity.getUpdatedAt() != null ? entity.getUpdatedAt().toLocalDateTime() : null);
    }

    /** 根据 ID 删除本能模板。 */
    @Override
    public void delete(UUID patternId) {
        jpaRepository.deleteById(patternId);
    }

    // ---- 转换方法 ----

    /** 将实体转换为领域对象。 */
    private InstinctPattern toDomain(InstinctPatternEntity entity) {
        return InstinctPattern.builder()
                .patternId(entity.getPatternId())
                .accountId(entity.getAccountId())
                .topologyJson(entity.getTopologyJson())
                .featureVector(deserializeFeatureVector(entity.getFeatureVector()))
                .executionMode(entity.getExecutionMode())
                .successCount(entity.getSuccessCount())
                .totalCount(entity.getTotalCount())
                .hitCount(entity.getHitCount())
                .successRate(entity.getSuccessRate())
                .weight(entity.getWeight())
                .draft(entity.getDraft())
                .version(entity.getVersion())
                .sourcePatternId(entity.getSourcePatternId())
                .active(entity.getActive())
                .lastMatchAt(entity.getLastMatchAt() != null ? entity.getLastMatchAt().toLocalDateTime() : null)
                .createdAt(entity.getCreatedAt() != null ? entity.getCreatedAt().toLocalDateTime() : null)
                .updatedAt(entity.getUpdatedAt() != null ? entity.getUpdatedAt().toLocalDateTime() : null)
                .build();
    }

    /** 将领域对象转换为实体。 */
    private InstinctPatternEntity toEntity(InstinctPattern pattern) {
        InstinctPatternEntity entity = new InstinctPatternEntity();
        entity.setPatternId(pattern.getPatternId());
        entity.setAccountId(pattern.getAccountId());
        entity.setTopologyJson(pattern.getTopologyJson());
        entity.setFeatureVector(serializeFeatureVector(pattern.getFeatureVector()));
        entity.setExecutionMode(pattern.getExecutionMode());
        entity.setSuccessCount(pattern.getSuccessCount() != null ? pattern.getSuccessCount() : 0);
        entity.setTotalCount(pattern.getTotalCount() != null ? pattern.getTotalCount() : 0);
        entity.setHitCount(pattern.getHitCount() != null ? pattern.getHitCount() : 0);
        entity.setSuccessRate(pattern.getSuccessRate());
        entity.setWeight(pattern.getWeight() != null ? pattern.getWeight() : 0.5);
        entity.setDraft(pattern.getDraft() != null ? pattern.getDraft() : false);
        entity.setVersion(pattern.getVersion() != null ? pattern.getVersion() : 1);
        entity.setSourcePatternId(pattern.getSourcePatternId());
        entity.setActive(pattern.getActive() != null ? pattern.getActive() : false);
        entity.setLastMatchAt(pattern.getLastMatchAt() != null
                ? pattern.getLastMatchAt().atOffset(ZoneOffset.UTC) : null);
        return entity;
    }

    // ---- JSON 序列化工具 ----

    private String serializeFeatureVector(PatternFeatureVector vector) {
        if (vector == null) return null;
        try {
            return MAPPER.writeValueAsString(vector);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("序列化 PatternFeatureVector 失败", e);
        }
    }

    private PatternFeatureVector deserializeFeatureVector(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return MAPPER.readValue(json, PatternFeatureVector.class);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
