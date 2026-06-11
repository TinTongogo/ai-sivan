package com.icusu.sivan.infra.forest.adapter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.icusu.sivan.domain.forest.template.GoalTreeTemplate;
import com.icusu.sivan.domain.forest.template.TemplateRepository;
import com.icusu.sivan.domain.forest.tree.ExecutableNode;
import com.icusu.sivan.infra.forest.entity.GoalTreeTemplateEntity;
import com.icusu.sivan.infra.forest.repository.TemplateJpaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 模板仓储适配器 — JPA 实现。
 * 树结构通过 Jackson 序列化为 JSONB 存储，反序列化时重建 ExecutableNode 实例。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TemplateRepositoryAdapter implements TemplateRepository {

    private final TemplateJpaRepository jpaRepository;
    private final ObjectMapper objectMapper;

    @Override
    public void save(GoalTreeTemplate template) {
        GoalTreeTemplateEntity entity = toEntity(template);
        jpaRepository.save(entity);
    }

    @Override
    public Optional<GoalTreeTemplate> findById(UUID templateId) {
        return jpaRepository.findById(templateId).flatMap(this::toDomain);
    }

    @Override
    public List<GoalTreeTemplate> findByAccountId(UUID accountId) {
        return jpaRepository.findByAccountIdOrderByUpdatedAtDesc(accountId).stream()
                .map(this::toDomain)
                .filter(Optional::isPresent)
                .map(o -> o.get())
                .toList();
    }

    @Override
    public List<GoalTreeTemplate> searchByName(UUID accountId, String keyword) {
        String lower = keyword.toLowerCase();
        return findByAccountId(accountId).stream()
                .filter(t -> t.name().toLowerCase().contains(lower)
                        || (t.description() != null && t.description().toLowerCase().contains(lower)))
                .toList();
    }

    @Override
    public void delete(UUID templateId) {
        jpaRepository.deleteById(templateId);
    }

    @Override
    public void updateStats(UUID templateId, int usageCount, int successCount) {
        jpaRepository.findById(templateId).ifPresent(entity -> {
            entity.setUsageCount(usageCount);
            entity.setSuccessCount(successCount);
            entity.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
            jpaRepository.save(entity);
        });
    }

    // ===== 转换方法 =====

    private GoalTreeTemplateEntity toEntity(GoalTreeTemplate template) {
        String rootJson;
        try {
            rootJson = objectMapper.writeValueAsString(template.root());
        } catch (JsonProcessingException e) {
            log.error("序列化模板树失败: templateId={}", template.templateId(), e);
            throw new RuntimeException("序列化模板树失败", e);
        }

        return GoalTreeTemplateEntity.builder()
                .templateId(template.templateId())
                .accountId(template.accountId())
                .name(template.name())
                .description(template.description())
                .rootJson(rootJson)
                .usageCount(template.usageCount())
                .successCount(template.successCount())
                .createdAt(template.createdAt() != null
                        ? OffsetDateTime.ofInstant(template.createdAt(), ZoneOffset.UTC) : OffsetDateTime.now(ZoneOffset.UTC))
                .updatedAt(OffsetDateTime.now(ZoneOffset.UTC))
                .build();
    }

    private Optional<GoalTreeTemplate> toDomain(GoalTreeTemplateEntity entity) {
        try {
            ExecutableNode root = objectMapper.readValue(entity.getRootJson(), ExecutableNode.class);
            return Optional.of(new GoalTreeTemplate(
                    entity.getAccountId(),
                    entity.getName(),
                    entity.getDescription(),
                    root
            ));
        } catch (JsonProcessingException e) {
            log.error("反序列化模板树失败: templateId={}", entity.getTemplateId(), e);
            return Optional.empty();
        }
    }
}
