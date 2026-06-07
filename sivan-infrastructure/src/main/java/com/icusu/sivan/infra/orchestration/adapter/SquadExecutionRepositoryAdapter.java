package com.icusu.sivan.infra.orchestration.adapter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.icusu.sivan.common.enums.ExecutionStatus;
import com.icusu.sivan.domain.orchestration.SquadExecution;
import com.icusu.sivan.domain.orchestration.ISquadExecutionRepository;
import com.icusu.sivan.infra.orchestration.entity.SquadExecutionEntity;
import com.icusu.sivan.infra.orchestration.repository.SquadExecutionJpaRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.TimeZone;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Squad 执行记录仓储适配器，实现 ISquadExecutionRepository。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SquadExecutionRepositoryAdapter implements ISquadExecutionRepository {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .setTimeZone(TimeZone.getTimeZone("UTC"));

    private final SquadExecutionJpaRepository jpaRepository;

    /** 根据 ID 查询执行记录。 */
    @Override
    public Optional<SquadExecution> findById(UUID executionId) {
        return jpaRepository.findById(executionId).map(this::toDomain);
    }

    /** 查询账号下的执行记录。 */
    @Override
    public List<SquadExecution> findByAccount(UUID accountId) {
        return jpaRepository.findByAccountId(accountId).stream()
                .map(this::toDomain).toList();
    }

    /** 根据 Squad ID 查询执行记录。 */
    @Override
    public List<SquadExecution> findBySquadId(UUID squadId) {
        return jpaRepository.findBySquadId(squadId).stream()
                .map(this::toDomain).toList();
    }

    /** 根据 Squad ID 和账号 ID 查询执行记录。 */
    @Override
    public List<SquadExecution> findBySquadIdAndAccountId(UUID squadId, UUID accountId) {
        return jpaRepository.findBySquadIdAndAccountId(squadId, accountId).stream()
                .map(this::toDomain).toList();
    }

    /** 分页根据 Squad ID 查询执行记录。 */
    @Override
    public List<SquadExecution> findBySquadIdPage(UUID squadId, int page, int size) {
        return jpaRepository.findBySquadId(squadId, PageRequest.of(page, size))
                .stream().map(this::toDomain).toList();
    }

    /** 统计指定 Squad 的执行记录总数。 */
    @Override
    public long countBySquadId(UUID squadId) {
        return jpaRepository.countBySquadId(squadId);
    }

    /** 保存执行记录，回写 ID 和时间戳。 */
    @Override
    public void save(SquadExecution execution) {
        SquadExecutionEntity entity = toEntity(execution);
        jpaRepository.save(entity);
        if (execution.getExecutionId() == null) {
            execution.setExecutionId(entity.getExecutionId());
        }
        execution.setCreatedAt(entity.getCreatedAt() != null ? entity.getCreatedAt().toLocalDateTime() : null);
    }

    /** 更新执行状态和错误信息。 */
    @Override
    public void updateStatus(UUID executionId, String status, String errorMessage) {
        SquadExecutionEntity entity = jpaRepository.findById(executionId).orElse(null);
        if (entity == null) {
            return;
        }
        entity.setStatus(status);
        entity.setErrorMessage(errorMessage);
        jpaRepository.save(entity);
    }

    /** 更新 Agent 状态快照（JSONB，用于 HITL 断点续跑）。 */
    @Override
    @Transactional
    public void updateAgentState(UUID executionId, String agentStateJson) {
        jpaRepository.updateAgentState(executionId, agentStateJson);
    }

    /** 更新执行结果（content/thinking）。 */
    @Override
    @Transactional
    public void updateResult(UUID executionId, String content, String thinking) {
        SquadExecutionEntity entity = jpaRepository.findById(executionId).orElse(null);
        if (entity == null) return;
        entity.setContent(content);
        entity.setThinking(thinking);
        jpaRepository.save(entity);
    }

    /** 更新当前执行阶段。 */
    @Override
    public void updateCurrentPhase(UUID executionId, int currentPhase) {
        SquadExecutionEntity entity = jpaRepository.findById(executionId).orElse(null);
        if (entity == null) {
            return;
        }
        entity.setCurrentPhase(currentPhase);
        jpaRepository.save(entity);
    }

    /** 统计账号下的执行记录总数。 */
    @Override
    public long countByAccount(UUID accountId) {
        return jpaRepository.countByAccountId(accountId);
    }

    /** 查询账号下最近的执行记录。 */
    @Override
    public List<SquadExecution> findRecentByAccount(UUID accountId, int limit) {
        return jpaRepository.findTop5ByAccountIdOrderByCreatedAtDesc(accountId).stream()
                .map(this::toDomain).toList();
    }

    /** 删除单条执行记录。 */
    @Override
    public void delete(UUID executionId) {
        jpaRepository.deleteById(executionId);
    }

    @Override
    public void deleteBatch(java.util.List<UUID> ids) {
        if (ids != null && !ids.isEmpty()) jpaRepository.deleteAllById(ids);
    }

    /** 删除 Squad 下的所有执行记录。 */
    @Override
    public void deleteBySquadId(UUID squadId) {
        jpaRepository.deleteBySquadId(squadId);
    }

    /** 分页按条件查询当前用户的执行记录。 */
    @Override
    public List<SquadExecution> findByAccountPage(UUID accountId, int page, int size, String status, UUID squadId) {
        Specification<SquadExecutionEntity> spec = (root, query, cb) -> {
            var predicates = new ArrayList<Predicate>();
            predicates.add(cb.equal(root.get("accountId"), accountId));
            if (status != null && !status.isBlank()) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (squadId != null) {
                predicates.add(cb.equal(root.get("squadId"), squadId));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        return jpaRepository.findAll(spec, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")))
                .stream().map(this::toDomain).toList();
    }

    /** 统计当前用户按状态的执行记录数。 */
    @Override
    public long countByAccountAndStatus(UUID accountId, String status) {
        return jpaRepository.countByAccountIdAndStatus(accountId, status);
    }

    /** 统计当前用户某时间之后特定状态的执行记录数。 */
    @Override
    public long countByAccountAndStatusSince(UUID accountId, String status, LocalDateTime since) {
        return jpaRepository.countByAccountIdAndStatusSince(accountId, status, since.atOffset(ZoneOffset.UTC));
    }

    // ---- 转换方法 ----

    /** 将实体转换为领域对象。 */
    private SquadExecution toDomain(SquadExecutionEntity entity) {
        return SquadExecution.builder()
                .executionId(entity.getExecutionId())
                .squadId(entity.getSquadId())
                .accountId(entity.getAccountId())
                .projectId(entity.getProjectId())
                .taskDescription(entity.getTaskDescription())
                .status(entity.getStatus() != null ? ExecutionStatus.valueOf(entity.getStatus()) : null)
                .topologySnapshot(entity.getTopologySnapshot())
                .context(parseJsonMap(entity.getContextJson()))
                .agentState(entity.getAgentState())
                .content(entity.getContent())
                .thinking(entity.getThinking())
                .currentPhase(entity.getCurrentPhase())
                .errorMessage(entity.getErrorMessage())
                .startedAt(entity.getStartedAt() != null ? entity.getStartedAt().toLocalDateTime() : null)
                .pausedAt(entity.getPausedAt() != null ? entity.getPausedAt().toLocalDateTime() : null)
                .completedAt(entity.getCompletedAt() != null ? entity.getCompletedAt().toLocalDateTime() : null)
                .createdAt(entity.getCreatedAt() != null ? entity.getCreatedAt().toLocalDateTime() : null)
                .build();
    }

    /** 将领域对象转换为实体。 */
    private SquadExecutionEntity toEntity(SquadExecution execution) {
        SquadExecutionEntity entity = new SquadExecutionEntity();
        entity.setExecutionId(execution.getExecutionId());
        entity.setSquadId(execution.getSquadId());
        entity.setAccountId(execution.getAccountId());
        entity.setProjectId(execution.getProjectId());
        entity.setTaskDescription(execution.getTaskDescription());
        entity.setStatus(execution.getStatus() != null ? execution.getStatus().name() : "PENDING");
        entity.setTopologySnapshot(execution.getTopologySnapshot());
        entity.setContextJson(toJsonString(execution.getContext()));
        entity.setAgentState(execution.getAgentState());
        entity.setContent(execution.getContent());
        entity.setThinking(execution.getThinking());
        entity.setCurrentPhase(execution.getCurrentPhase());
        entity.setErrorMessage(execution.getErrorMessage());
        if (execution.getStartedAt() != null) {
            entity.setStartedAt(execution.getStartedAt().atOffset(ZoneOffset.UTC));
        }
        if (execution.getPausedAt() != null) {
            entity.setPausedAt(execution.getPausedAt().atOffset(ZoneOffset.UTC));
        }
        if (execution.getCompletedAt() != null) {
            entity.setCompletedAt(execution.getCompletedAt().atOffset(ZoneOffset.UTC));
        }
        return entity;
    }

    /** 将对象序列化为 JSON 字符串。 */
    private String toJsonString(Object obj) {
        if (obj == null) {
            return "{}";
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(obj);
        } catch (Exception e) { log.warn("JSON 序列化/反序列化失败", e);
            return "{}";
        }
    }

    /** 将 JSON 字符串解析为 Map。 */
    private Map<String, Object> parseJsonMap(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            return OBJECT_MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) { log.warn("JSON 序列化/反序列化失败", e);
            return Collections.emptyMap();
        }
    }
}
