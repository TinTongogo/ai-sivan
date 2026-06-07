package com.icusu.sivan.infra.memory.adapter;

import com.icusu.sivan.domain.memory.ISharedTemplateRepository;
import com.icusu.sivan.domain.memory.SharedTemplate;
import com.icusu.sivan.infra.memory.entity.SharedTemplateEntity;
import com.icusu.sivan.infra.memory.repository.SharedTemplateJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 共享模板仓储适配器，实现 ISharedTemplateRepository。
 */
@Component
@RequiredArgsConstructor
public class SharedTemplateRepositoryAdapter implements ISharedTemplateRepository {

    private final SharedTemplateJpaRepository jpaRepository;

    @Override
    public void save(SharedTemplate template) {
        SharedTemplateEntity entity = toEntity(template);
        jpaRepository.save(entity);
        template.setTemplateId(entity.getTemplateId());
        if (entity.getCreatedAt() != null) {
            template.setCreatedAt(entity.getCreatedAt().toLocalDateTime());
            template.setUpdatedAt(entity.getUpdatedAt().toLocalDateTime());
        }
    }

    @Override
    public Optional<SharedTemplate> findById(UUID templateId) {
        return jpaRepository.findById(templateId).map(this::toDomain);
    }

    @Override
    public List<SharedTemplate> findByOwner(UUID ownerAccountId) {
        return jpaRepository.findByOwnerAccountIdOrderByCreatedAtDesc(ownerAccountId).stream()
                .map(this::toDomain).toList();
    }

    @Override
    public List<SharedTemplate> findByVisibility(SharedTemplate.Visibility visibility) {
        return jpaRepository.findByVisibilityOrderByCreatedAtDesc(visibility.name()).stream()
                .map(this::toDomain).toList();
    }

    @Override
    public List<SharedTemplate> findByVisibilityAndNotOwner(SharedTemplate.Visibility visibility,
                                                             UUID ownerAccountId) {
        return jpaRepository.findByVisibilityAndOwnerAccountIdNotOrderByCreatedAtDesc(
                visibility.name(), ownerAccountId).stream()
                .map(this::toDomain).toList();
    }

    @Override
    public List<SharedTemplate> findByAllowedAccount(UUID accountId) {
        // LIST 可见性查询：allowed_accounts JSON 数组包含该 accountId
        // 通过 JPQL 或原生 SQL 查询，此处使用 LIKE 匹配（JSON 数组格式 ["id1","id2"]）
        return jpaRepository.findByVisibilityOrderByCreatedAtDesc("LIST").stream()
                .filter(e -> e.getAllowedAccounts() != null
                        && e.getAllowedAccounts().contains(accountId.toString()))
                .map(this::toDomain)
                .toList();
    }

    @Override
    public void delete(UUID templateId) {
        jpaRepository.deleteById(templateId);
    }

    @Override
    public void markOrphanedByOwner(UUID ownerAccountId) {
        List<SharedTemplateEntity> entities = jpaRepository
                .findByOwnerAccountIdOrderByCreatedAtDesc(ownerAccountId);
        for (SharedTemplateEntity entity : entities) {
            entity.setStatus("ORPHANED");
            jpaRepository.save(entity);
        }
    }

    // ---- 转换方法 ----

    private SharedTemplate toDomain(SharedTemplateEntity entity) {
        return SharedTemplate.builder()
                .templateId(entity.getTemplateId())
                .patternId(entity.getPatternId())
                .ownerAccountId(entity.getOwnerAccountId())
                .visibility(SharedTemplate.Visibility.valueOf(entity.getVisibility()))
                .projectId(entity.getProjectId())
                .allowedAccounts(entity.getAllowedAccounts())
                .status(entity.getStatus())
                .quality(entity.getQuality())
                .useCount(entity.getUseCount())
                .successCount(entity.getSuccessCount())
                .sharedAt(entity.getSharedAt() != null
                        ? entity.getSharedAt().toLocalDateTime() : null)
                .createdAt(entity.getCreatedAt() != null
                        ? entity.getCreatedAt().toLocalDateTime() : null)
                .updatedAt(entity.getUpdatedAt() != null
                        ? entity.getUpdatedAt().toLocalDateTime() : null)
                .build();
    }

    private SharedTemplateEntity toEntity(SharedTemplate domain) {
        return SharedTemplateEntity.builder()
                .templateId(domain.getTemplateId())
                .patternId(domain.getPatternId())
                .ownerAccountId(domain.getOwnerAccountId())
                .visibility(domain.getVisibility().name())
                .projectId(domain.getProjectId())
                .allowedAccounts(domain.getAllowedAccounts())
                .status(domain.getStatus())
                .quality(domain.getQuality())
                .useCount(domain.getUseCount())
                .successCount(domain.getSuccessCount())
                .sharedAt(domain.getSharedAt() != null
                        ? domain.getSharedAt().atOffset(ZoneOffset.UTC) : null)
                .build();
    }
}
