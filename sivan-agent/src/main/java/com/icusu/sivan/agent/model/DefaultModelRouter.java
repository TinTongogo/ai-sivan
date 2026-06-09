package com.icusu.sivan.agent.model;

import com.icusu.sivan.common.exception.DomainException;
import com.icusu.sivan.core.model.Model;
import com.icusu.sivan.core.model.ModelAccessor;
import com.icusu.sivan.domain.model.LlmProvider;
import com.icusu.sivan.domain.model.ILlmProviderRepository;
import com.icusu.sivan.domain.model.ModelCapability;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class DefaultModelRouter implements ModelRouter, ModelAccessor {
    private final ILlmProviderRepository providerRepository;
    private final ModelCapabilityRegistry capabilityRegistry;
    private final Map<UUID, CacheEntry> modelCache = new ConcurrentHashMap<>();
    private final Map<UUID, CacheEntry> defaultModelCache = new ConcurrentHashMap<>();
    private static final Duration TTL = Duration.ofMinutes(30);

    private record CacheEntry(Model model, Instant createdAt) {}

    public DefaultModelRouter(ILlmProviderRepository providerRepository,
                              ModelCapabilityRegistry capabilityRegistry) {
        this.providerRepository = providerRepository;
        this.capabilityRegistry = capabilityRegistry;
    }

    @Override
    public Model getDefaultModel(UUID accountId) {
        CacheEntry entry = defaultModelCache.get(accountId);
        if (entry != null && !entry.createdAt.plus(TTL).isBefore(Instant.now())) {
            return entry.model;
        }
        defaultModelCache.remove(accountId);
        return defaultModelCache.computeIfAbsent(accountId, id -> {
            LlmProvider provider = getDefaultProvider(id);
            return new CacheEntry(resolveModel(provider), Instant.now());
        }).model;
    }

    @Override
    public LlmProvider getDefaultProvider(UUID accountId) {
        return findProviderByTag(accountId, "chat")
                .orElseThrow(() -> new DomainException(400, "error.llm.provider.not-configured", "对话"));
    }

    @Override
    public LlmProvider getEmbeddingProvider(UUID accountId) {
        return findProviderByTag(accountId, "embedding")
                .orElseThrow(() -> new DomainException(400, "error.llm.provider.not-configured", "Embedding"));
    }

    @Override
    public Model getLightModel(UUID accountId) {
        // 优先使用 tag=light 的轻量模型，未配置时降级到默认对话模型
        return findProviderByTag(accountId, "light")
                .map(this::resolveModel)
                .orElseGet(() -> getDefaultModel(accountId));
    }

    @Override
    public Model getEmbeddingModel(UUID accountId) {
        return resolveModel(getEmbeddingProvider(accountId));
    }

    private Optional<LlmProvider> findProviderByTag(UUID accountId, String tag) {
        return providerRepository.findDefaultByAccount(accountId)
                .filter(p -> p.getTags() != null && p.getTags().contains(tag))
                .or(() -> providerRepository.findByAccountAndTagsContains(accountId, tag).stream()
                        .findFirst())
                .or(() -> providerRepository.findActiveByAccount(accountId).stream()
                        .filter(p -> p.getTags() != null && p.getTags().contains(tag))
                        .findFirst());
    }

    @Override
    public LlmProvider getProvider(UUID providerId) {
        return providerRepository.findById(providerId)
                .orElseThrow(() -> DomainException.notFound("LLM Provider", providerId));
    }

    @Override
    public Model getModel(UUID providerId) {
        CacheEntry entry = modelCache.get(providerId);
        if (entry != null && !entry.createdAt.plus(TTL).isBefore(Instant.now())) {
            return entry.model;
        }
        // 缓存未命中或已过期，重新加载
        modelCache.remove(providerId);
        return modelCache.computeIfAbsent(providerId, id -> {
            var p = providerRepository.findById(id)
                    .orElseThrow(() -> DomainException.notFound("LLM Provider", id));
            return new CacheEntry(resolveModel(p), Instant.now());
        }).model;
    }

    private Model resolveModel(LlmProvider provider) {
        // 合并 DB 中用户显式配置的能力 + 模型名前缀推断的能力，确保两套来源一致
        Set<ModelCapability> inferred = capabilityRegistry.infer(
                provider.getPrimaryModelName(), provider.getProviderType());
        Set<ModelCapability> merged = new java.util.HashSet<>(inferred);
        merged.addAll(provider.getCapabilitySet());
        return new OpenAiModel(provider.getBaseUrl(),
                provider.getApiKey() != null ? provider.getApiKey() : "",
                provider.getPrimaryModelName(),
                Duration.ofSeconds(120), merged);
    }

    @Override
    public void evictCache(UUID providerId) {
        modelCache.remove(providerId);
        // provider 变更可能影响默认模型，清空默认模型缓存
        defaultModelCache.clear();
    }

    /** 清除指定账户的默认模型缓存。 */
    public void evictDefaultCache(UUID accountId) {
        defaultModelCache.remove(accountId);
    }
}
