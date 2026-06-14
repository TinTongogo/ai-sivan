package com.icusu.sivan.infra.model.adapter;

import com.icusu.sivan.domain.model.LlmProvider;
import com.icusu.sivan.domain.model.ILlmProviderRepository;
import com.icusu.sivan.infra.model.entity.LlmProviderEntity;
import com.icusu.sivan.infra.model.repository.LlmProviderJpaRepository;
import com.icusu.sivan.infra.shared.security.ApiKeyEncryptor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * LLM 提供商仓储适配器，实现 ILlmProviderRepository。
 */
@Component
@RequiredArgsConstructor
public class LlmProviderRepositoryAdapter implements ILlmProviderRepository {

    private final LlmProviderJpaRepository jpaRepository;
    private final ApiKeyEncryptor apiKeyEncryptor;

    /** 根据 ID 查询 LLM 提供商。 */
    @Override
    public Optional<LlmProvider> findById(UUID providerId) {
        return jpaRepository.findById(providerId).map(this::toDomain);
    }

    /** 查询账号下所有 LLM 提供商。 */
    @Override
    public List<LlmProvider> findAllByAccount(UUID accountId) {
        return jpaRepository.findByAccountId(accountId).stream()
                .map(this::toDomain).toList();
    }

    /** 查询账号下已激活的 LLM 提供商。 */
    @Override
    public List<LlmProvider> findActiveByAccount(UUID accountId) {
        return jpaRepository.findByAccountIdAndActiveTrue(accountId).stream()
                .map(this::toDomain).toList();
    }

    /** 查询账号下的 Chat 默认 LLM 提供商。 */
    @Override
    public Optional<LlmProvider> findDefaultByAccount(UUID accountId) {
        return jpaRepository.findByAccountIdAndIsChatDefaultTrue(accountId)
                .map(this::toDomain);
    }

    /** 根据 tag 查找 LLM 提供商。 */
    @Override
    public List<LlmProvider> findByTagsContains(String tag) {
        return jpaRepository.findByTagsContaining(tag).stream()
                .map(this::toDomain).toList();
    }

    /** 根据账户和 tag 查找 LLM 提供商。 */
    @Override
    public List<LlmProvider> findByAccountAndTagsContains(UUID accountId, String tag) {
        return jpaRepository.findByAccountIdAndTagsContaining(accountId, tag).stream()
                .map(this::toDomain).toList();
    }

    /** 立即刷新待处理的数据库变更。 */
    @Override
    public void flush() {
        jpaRepository.flush();
    }

    /** 保存 LLM 提供商，回写 ID 和时间戳。 */
    @Override
    public void save(LlmProvider provider) {
        LlmProviderEntity entity = toEntity(provider);
        jpaRepository.save(entity);
        if (provider.getProviderId() == null) {
            provider.setProviderId(entity.getProviderId());
        }
        provider.setCreatedAt(entity.getCreatedAt() != null ? entity.getCreatedAt().toLocalDateTime() : null);
        provider.setUpdatedAt(entity.getUpdatedAt() != null ? entity.getUpdatedAt().toLocalDateTime() : null);
    }

    /** 根据 ID 删除 LLM 提供商。 */
    @Override
    public void delete(UUID providerId) {
        jpaRepository.deleteById(providerId);
    }

    // ---- 转换方法 ----

    /** 将实体转换为领域对象。 */
    private LlmProvider toDomain(LlmProviderEntity entity) {
        return LlmProvider.builder()
                .providerId(entity.getProviderId())
                .accountId(entity.getAccountId())
                .name(entity.getName())
                .providerType(entity.getProviderType())
                .apiKey(apiKeyEncryptor.decrypt(entity.getApiKey()))
                .baseUrl(entity.getBaseUrl())
                .models(entity.getModels())
                .capabilities(entity.getCapabilities())
                .contextLength(entity.getContextLength())
                .tags(entity.getTags())
                .active(entity.getActive())
                .isChatDefault(entity.getIsChatDefault())
                .isEmbedDefault(entity.getIsEmbedDefault())
                .isRerankDefault(entity.getIsRerankDefault())
                .temperature(entity.getTemperature())
                .createdAt(entity.getCreatedAt() != null ? entity.getCreatedAt().toLocalDateTime() : null)
                .updatedAt(entity.getUpdatedAt() != null ? entity.getUpdatedAt().toLocalDateTime() : null)
                .build();
    }

    /** 将领域对象转换为实体。 */
    private LlmProviderEntity toEntity(LlmProvider provider) {
        LlmProviderEntity entity = new LlmProviderEntity();
        entity.setProviderId(provider.getProviderId());
        entity.setAccountId(provider.getAccountId());
        entity.setName(provider.getName());
        entity.setProviderType(provider.getProviderType());
        entity.setApiKey(apiKeyEncryptor.encrypt(provider.getApiKey()));
        entity.setBaseUrl(provider.getBaseUrl());
        entity.setModels(provider.getModels());
        entity.setCapabilities(provider.getCapabilities());
        entity.setContextLength(provider.getContextLength() != null ? provider.getContextLength() : 4096);
        entity.setTags(provider.getTags() != null ? provider.getTags() : "");
        entity.setActive(provider.getActive() != null ? provider.getActive() : true);
        entity.setIsChatDefault(provider.getIsChatDefault() != null ? provider.getIsChatDefault() : false);
        entity.setIsEmbedDefault(provider.getIsEmbedDefault() != null ? provider.getIsEmbedDefault() : false);
        entity.setIsRerankDefault(provider.getIsRerankDefault() != null ? provider.getIsRerankDefault() : false);
        entity.setTemperature(provider.getTemperature());
        return entity;
    }
}
