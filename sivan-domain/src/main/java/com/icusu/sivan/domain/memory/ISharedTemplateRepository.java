package com.icusu.sivan.domain.memory;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 共享模板仓储接口。
 */
public interface ISharedTemplateRepository {

    void save(SharedTemplate template);

    Optional<SharedTemplate> findById(UUID templateId);

    List<SharedTemplate> findByOwner(UUID ownerAccountId);

    List<SharedTemplate> findByVisibility(SharedTemplate.Visibility visibility);

    List<SharedTemplate> findByVisibilityAndNotOwner(SharedTemplate.Visibility visibility, UUID ownerAccountId);

    List<SharedTemplate> findByAllowedAccount(UUID accountId);

    void delete(UUID templateId);

    void markOrphanedByOwner(UUID ownerAccountId);
}
