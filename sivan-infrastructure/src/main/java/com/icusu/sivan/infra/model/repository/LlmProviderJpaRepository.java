package com.icusu.sivan.infra.model.repository;

import com.icusu.sivan.infra.model.entity.LlmProviderEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * LLM 提供商配置表数据访问接口。
 */
@Repository
public interface LlmProviderJpaRepository extends JpaRepository<LlmProviderEntity, UUID> {

    List<LlmProviderEntity> findByAccountId(UUID accountId);

    List<LlmProviderEntity> findByAccountIdAndActiveTrue(UUID accountId);

    Optional<LlmProviderEntity> findByAccountIdAndIsChatDefaultTrue(UUID accountId);

    List<LlmProviderEntity> findByTagsContaining(String tag);

    List<LlmProviderEntity> findByAccountIdAndTagsContaining(UUID accountId, String tag);
}
