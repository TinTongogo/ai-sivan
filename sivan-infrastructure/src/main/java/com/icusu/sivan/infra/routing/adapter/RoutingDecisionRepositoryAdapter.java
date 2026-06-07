package com.icusu.sivan.infra.routing.adapter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.icusu.sivan.domain.routing.RoutingDecision;
import com.icusu.sivan.domain.routing.IRoutingDecisionRepository;
import com.icusu.sivan.infra.routing.entity.RoutingDecisionEntity;
import com.icusu.sivan.infra.routing.repository.RoutingDecisionJpaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.TimeZone;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

/**
 * 路由决策仓储适配器，实现 IRoutingDecisionRepository。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RoutingDecisionRepositoryAdapter implements IRoutingDecisionRepository {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .setTimeZone(TimeZone.getTimeZone("UTC"));

    private final RoutingDecisionJpaRepository jpaRepository;

    /** 保存路由决策，回写 ID 和时间戳。 */
    @Override
    public void save(RoutingDecision decision) {
        RoutingDecisionEntity entity = toEntity(decision);
        jpaRepository.save(entity);
        if (decision.getDecisionId() == null) {
            decision.setDecisionId(entity.getDecisionId());
        }
        decision.setCreatedAt(entity.getCreatedAt() != null ? entity.getCreatedAt().toLocalDateTime() : null);
    }

    /** 根据 ID 查询路由决策。 */
    @Override
    public Optional<RoutingDecision> findById(UUID decisionId) {
        return jpaRepository.findById(decisionId).map(this::toDomain);
    }

    /** 查询账号下的路由决策历史。 */
    @Override
    public List<RoutingDecision> findByAccount(UUID accountId) {
        return jpaRepository.findByAccountId(accountId).stream()
                .map(this::toDomain).toList();
    }

    /** 根据账号和策略查询路由决策。 */
    @Override
    public List<RoutingDecision> findByAccountAndStrategy(UUID accountId, String strategy) {
        return jpaRepository.findByAccountIdAndStrategy(accountId, strategy).stream()
                .map(this::toDomain).toList();
    }

    /** 分页查询路由决策（按创建时间降序）。 */
    @Override
    public List<RoutingDecision> findByAccountPage(UUID accountId, int page, int size) {
        return jpaRepository.findByAccountIdOrderByCreatedAtDesc(accountId,
                        PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")))
                .stream().map(this::toDomain).toList();
    }

    /** 分页查询路由决策（按策略过滤，按创建时间降序）。 */
    @Override
    public List<RoutingDecision> findByAccountAndStrategyPage(UUID accountId, String strategy, int page, int size) {
        return jpaRepository.findByAccountIdAndStrategyOrderByCreatedAtDesc(accountId, strategy,
                        PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")))
                .stream().map(this::toDomain).toList();
    }

    /** 统计指定用户的路由决策总数。 */
    @Override
    public long countByAccount(UUID accountId) {
        return jpaRepository.countByAccountId(accountId);
    }

    /** 删除指定路由决策。 */
    @Override
    public void deleteById(UUID decisionId) {
        jpaRepository.deleteById(decisionId);
    }

    /** 批量删除路由决策。 */
    @Override
    public void deleteBatch(List<UUID> decisionIds) {
        jpaRepository.deleteAllById(decisionIds);
    }

    // ---- 转换方法 ----

    /** 将实体转换为领域对象。 */
    private RoutingDecision toDomain(RoutingDecisionEntity entity) {
        return RoutingDecision.builder()
                .decisionId(entity.getDecisionId())
                .accountId(entity.getAccountId())
                .projectId(entity.getProjectId())
                .conversationId(entity.getConversationId())
                .taskDescription(entity.getTaskDescription())
                .selectedAgentName(entity.getSelectedAgent())
                .strategy(entity.getStrategy())
                .success(entity.getSuccess())
                .confidence(entity.getConfidence())
                .context(parseJsonMap(entity.getContextJson()))
                .errorHint(entity.getErrorHint())
                .reasoning(entity.getReasoning())
                .createdAt(entity.getCreatedAt() != null ? entity.getCreatedAt().toLocalDateTime() : null)
                .build();
    }

    /** 将领域对象转换为实体。 */
    private RoutingDecisionEntity toEntity(RoutingDecision decision) {
        RoutingDecisionEntity entity = new RoutingDecisionEntity();
        entity.setDecisionId(decision.getDecisionId());
        entity.setAccountId(decision.getAccountId());
        entity.setProjectId(decision.getProjectId());
        entity.setConversationId(decision.getConversationId());
        entity.setTaskDescription(decision.getTaskDescription());
        entity.setSelectedAgent(decision.getSelectedAgentName());
        entity.setStrategy(decision.getStrategy());
        entity.setSuccess(decision.getSuccess() != null && decision.getSuccess());
        entity.setConfidence(decision.getConfidence());
        entity.setContextJson(toJsonString(decision.getContext()));
        entity.setErrorHint(decision.getErrorHint());
        entity.setReasoning(decision.getReasoning());
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
