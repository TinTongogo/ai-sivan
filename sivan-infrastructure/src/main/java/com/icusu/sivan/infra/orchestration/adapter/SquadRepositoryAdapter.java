package com.icusu.sivan.infra.orchestration.adapter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.icusu.sivan.common.enums.SquadMode;
import com.icusu.sivan.common.enums.SquadSource;
import com.icusu.sivan.domain.orchestration.PhaseNode;
import com.icusu.sivan.domain.orchestration.Squad;
import com.icusu.sivan.domain.orchestration.ISquadRepository;
import com.icusu.sivan.infra.orchestration.entity.SquadEntity;
import com.icusu.sivan.infra.orchestration.repository.SquadJpaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.TimeZone;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Squad 编排模板仓储适配器，实现 ISquadRepository。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SquadRepositoryAdapter implements ISquadRepository {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .setTimeZone(TimeZone.getTimeZone("UTC"));

    private final SquadJpaRepository jpaRepository;

    /** 根据 ID 查询 Squad。 */
    @Override
    public Optional<Squad> findById(UUID squadId) {
        return jpaRepository.findById(squadId).map(this::toDomain);
    }

    /** 查询账号下所有 Squad。 */
    @Override
    public List<Squad> findAllByAccount(UUID accountId) {
        return jpaRepository.findByAccountId(accountId).stream()
                .map(this::toDomain).toList();
    }

    /** 查询账号下所有已启用的 Squad。 */
    @Override
    public List<Squad> findAllByAccountAndActiveTrue(UUID accountId) {
        return jpaRepository.findByAccountIdAndActiveTrue(accountId).stream()
                .map(this::toDomain).toList();
    }

    /** 查询账号下指定项目的 Squad。 */
    @Override
    public List<Squad> findAllByAccountAndProject(UUID accountId, UUID projectId) {
        return jpaRepository.findByAccountIdAndProjectId(accountId, projectId).stream()
                .map(this::toDomain).toList();
    }

    /** 保存 Squad，回写 ID 和时间戳。 */
    @Override
    public void save(Squad squad) {
        SquadEntity entity = toEntity(squad);
        jpaRepository.save(entity);
        if (squad.getSquadId() == null) {
            squad.setSquadId(entity.getSquadId());
        }
        squad.setCreatedAt(entity.getCreatedAt() != null ? entity.getCreatedAt().toLocalDateTime() : null);
        squad.setUpdatedAt(entity.getUpdatedAt() != null ? entity.getUpdatedAt().toLocalDateTime() : null);
    }

    /** 更新 Squad 信息。 */
    @Override
    public void update(Squad squad) {
        SquadEntity entity = jpaRepository.findById(squad.getSquadId()).orElse(null);
        if (entity == null) {
            return;
        }
        entity.setName(squad.getName());
        entity.setDescription(squad.getDescription());
        entity.setMode(squad.getMode() != null ? squad.getMode().name() : entity.getMode());
        entity.setTopologyJson(toJsonString(squad.getPhases()));
        entity.setActive(squad.getActive());
        entity.setUsageCount(squad.getUsageCount() != null ? squad.getUsageCount() : entity.getUsageCount());
        entity.setSuccessRate(squad.getSuccessRate() != null ? squad.getSuccessRate() : entity.getSuccessRate());
        jpaRepository.save(entity);
        squad.setUpdatedAt(entity.getUpdatedAt() != null ? entity.getUpdatedAt().toLocalDateTime() : null);
    }

    /** 根据 ID 删除 Squad。 */
    @Override
    public void delete(UUID squadId) {
        jpaRepository.deleteById(squadId);
    }

    @Override
    public void deleteBatch(java.util.List<UUID> ids) {
        if (ids != null && !ids.isEmpty()) jpaRepository.deleteAllById(ids);
    }

    /** 分页查询账号下所有 Squad。 */
    @Override
    public List<Squad> findAllByAccountPage(UUID accountId, int page, int size) {
        return jpaRepository.findByAccountId(accountId, PageRequest.of(page, size))
                .stream().map(this::toDomain).toList();
    }

    /** 分页查询账号下指定项目的 Squad。 */
    @Override
    public List<Squad> findAllByAccountAndProjectPage(UUID accountId, UUID projectId, int page, int size) {
        return jpaRepository.findByAccountIdAndProjectId(accountId, projectId, PageRequest.of(page, size))
                .stream().map(this::toDomain).toList();
    }

    /** 统计账号下 Squad 总数。 */
    @Override
    public long countByAccount(UUID accountId) {
        return jpaRepository.countByAccountId(accountId);
    }

    // ---- 转换方法 ----

    /** 将实体转换为领域对象。 */
    private Squad toDomain(SquadEntity entity) {
        return Squad.builder()
                .squadId(entity.getSquadId())
                .accountId(entity.getAccountId())
                .projectId(entity.getProjectId())
                .name(entity.getName())
                .description(entity.getDescription())
                .mode(entity.getMode() != null ? SquadMode.valueOf(entity.getMode()) : null)
                .source(entity.getSource() != null ? SquadSource.valueOf(entity.getSource()) : SquadSource.USER)
                .sourcePatternId(entity.getSourcePatternId())
                .active(entity.getActive())
                .phases(parsePhaseList(entity.getTopologyJson()))
                .usageCount(entity.getUsageCount())
                .successRate(entity.getSuccessRate())
                .createdAt(entity.getCreatedAt() != null ? entity.getCreatedAt().toLocalDateTime() : null)
                .updatedAt(entity.getUpdatedAt() != null ? entity.getUpdatedAt().toLocalDateTime() : null)
                .build();
    }

    /** 将领域对象转换为实体。 */
    private SquadEntity toEntity(Squad squad) {
        SquadEntity entity = new SquadEntity();
        entity.setSquadId(squad.getSquadId());
        entity.setAccountId(squad.getAccountId());
        entity.setProjectId(squad.getProjectId());
        entity.setName(squad.getName());
        entity.setDescription(squad.getDescription());
        entity.setMode(squad.getMode() != null ? squad.getMode().name() : null);
        entity.setTopologyJson(toJsonString(squad.getPhases()));
        entity.setActive(squad.getActive() != null ? squad.getActive() : true);
        entity.setUsageCount(squad.getUsageCount() != null ? squad.getUsageCount() : 0);
        entity.setSuccessRate(squad.getSuccessRate());
        entity.setSource(squad.getSource() != null ? squad.getSource().name() : "USER");
        entity.setSourcePatternId(squad.getSourcePatternId());
        return entity;
    }

    /** 将 JSON 字符串解析为阶段列表。 */
    private List<PhaseNode> parsePhaseList(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return OBJECT_MAPPER.readValue(json, new TypeReference<List<PhaseNode>>() {});
        } catch (Exception e) { log.warn("JSON 序列化/反序列化失败", e);
            return Collections.emptyList();
        }
    }

    /** 将对象序列化为 JSON 字符串。 */
    private String toJsonString(Object obj) {
        if (obj == null) {
            return "[]";
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(obj);
        } catch (Exception e) { log.warn("JSON 序列化/反序列化失败", e);
            return "[]";
        }
    }
}
