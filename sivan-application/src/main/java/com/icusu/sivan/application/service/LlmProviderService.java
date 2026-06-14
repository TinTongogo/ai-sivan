package com.icusu.sivan.application.service;

import com.icusu.sivan.agent.model.ModelCapabilityRegistry;
import com.icusu.sivan.common.exception.DomainException;
import com.icusu.sivan.common.exception.ResourceNotFoundException;
import com.icusu.sivan.common.util.UrlValidator;
import com.icusu.sivan.domain.model.LlmProvider;
import com.icusu.sivan.domain.model.ILlmProviderRepository;
import com.icusu.sivan.domain.model.ModelCapability;
import com.icusu.sivan.agent.model.ModelRouter;
import com.icusu.sivan.application.model.dto.*;
import com.icusu.sivan.infra.model.LlmHttpClient;
import com.icusu.sivan.infra.model.LlmHttpClient.TestResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** LLM 提供商管理服务，管理模型提供商配置、连通性测试与模型列表获取。 */
@Slf4j
@Service
public class LlmProviderService {

    private final ILlmProviderRepository llmProviderRepository;
    private final ModelRouter modelRouter;
    private final ModelCapabilityRegistry capabilityRegistry;
    private final LlmHttpClient llmHttpClient;

    public LlmProviderService(ILlmProviderRepository llmProviderRepository,
                              ModelRouter modelRouter, ModelCapabilityRegistry capabilityRegistry,
                              LlmHttpClient llmHttpClient) {
        this.llmProviderRepository = llmProviderRepository;
        this.modelRouter = modelRouter;
        this.capabilityRegistry = capabilityRegistry;
        this.llmHttpClient = llmHttpClient;
    }

    /** 获取所有可用的模型能力列表。 */
    public List<ModelCapabilityInfo> getAllCapabilities() {
        return List.of(ModelCapability.values()).stream()
                .map(c -> ModelCapabilityInfo.builder()
                        .code(c.getCode()).label(c.getLabel()).build())
                .toList();
    }

    /** 获取指定 providerType 的默认能力编码列表。 */
    public List<String> getDefaultCapabilities(String providerType) {
        return capabilityRegistry.getDefaults(providerType).stream()
                .map(ModelCapability::getCode)
                .toList();
    }

    /** 根据模型名和 providerType 推断能力编码列表。 */
    public List<String> inferCapabilities(String modelName, String providerType) {
        return capabilityRegistry.infer(modelName, providerType).stream()
                .map(ModelCapability::getCode)
                .toList();
    }

    /** 创建 LLM 提供商。 */
    @CacheEvict(cacheNames = "llmProviders", allEntries = true)
    public LlmProviderResponse create(UUID accountId, CreateLlmProviderRequest request) {
        String tags = request.getTags();
        boolean needsChat = tags != null && tags.contains("chat");
        String caps = request.getCapabilities();
        if (needsChat && (caps == null || caps.isBlank())) {
            throw new DomainException(400, "error.llm.provider.capabilities-required");
        }
        if (request.getBaseUrl() != null && !request.getBaseUrl().isBlank()) {
            var urlCheck = UrlValidator.validate(request.getBaseUrl());
            if (!urlCheck.valid()) {
                throw new DomainException(400, "error.llm.provider.url-invalid", urlCheck.errorMessage());
            }
        }
        LlmProvider provider = LlmProvider.builder()
                .accountId(accountId)
                .name(request.getName())
                .providerType(request.getProviderType())
                .apiKey(request.getApiKey())
                .baseUrl(request.getBaseUrl())
                .models(request.getModel())
                .capabilities(caps)
                .tags(request.getTags())
                .active(true)
                .temperature(request.getTemperature())
                .build();
        llmProviderRepository.save(provider);

        Integer ctxLen = request.getContextLength() != null
                ? request.getContextLength()
                : llmHttpClient.resolveContextLength(request.getProviderType(), request.getApiKey(),
                        request.getBaseUrl(), request.getModel());
        provider.setContextLength(ctxLen);
        llmProviderRepository.save(provider);
        return toResponse(provider);
    }

    /** 根据 ID 查询 LLM 提供商。 */
    public LlmProviderResponse getById(UUID accountId, UUID providerId) {
        return toResponse(findOwned(accountId, providerId));
    }

    /** 查询 LLM 提供商列表。 */
    public List<LlmProviderResponse> list(UUID accountId) {
        return llmProviderRepository.findAllByAccount(accountId).stream()
                .map(this::toResponse).toList();
    }

    /** 更新 LLM 提供商配置。 */
    @CacheEvict(cacheNames = "llmProviders", allEntries = true)
    public LlmProviderResponse update(UUID accountId, UUID providerId, UpdateLlmProviderRequest request) {
        LlmProvider provider = findOwned(accountId, providerId);
        if (request.getBaseUrl() != null && !request.getBaseUrl().isBlank()) {
            var urlCheck = UrlValidator.validate(request.getBaseUrl());
            if (!urlCheck.valid()) {
                throw new DomainException(400, "error.llm.provider.url-invalid", urlCheck.errorMessage());
            }
        }
        provider.updateFrom(request.getName(), request.getProviderType(), request.getApiKey(),
                request.getBaseUrl(), request.getModel(), request.getCapabilities(), request.getActive(),
                request.getTemperature(), request.getContextLength());
        if (request.getTags() != null) provider.setTags(request.getTags());
        if (request.getIsDefault() != null) setDefault(accountId, providerId, request.getIsDefault());

        llmProviderRepository.save(provider);
        if (request.getContextLength() == null
                && (request.getBaseUrl() != null || request.getModel() != null)) {
            String apiKey = request.getApiKey() != null ? request.getApiKey() : provider.getApiKey();
            String baseUrl = request.getBaseUrl() != null ? request.getBaseUrl() : provider.getBaseUrl();
            String model = request.getModel() != null ? request.getModel() : provider.getModels();
            Integer ctxLen = llmHttpClient.resolveContextLength(provider.getProviderType(), apiKey, baseUrl, model);
            provider.setContextLength(ctxLen);
            llmProviderRepository.save(provider);
        }
        modelRouter.evictCache(providerId);
        return toResponse(provider);
    }

    /** 删除 LLM 提供商。 */
    @CacheEvict(cacheNames = "llmProviders", allEntries = true)
    public void delete(UUID accountId, UUID providerId) {
        LlmProvider provider = findOwned(accountId, providerId);
        llmProviderRepository.delete(provider.getProviderId());
    }

    /** 设置默认 LLM 提供商。 */
    @Transactional
    public LlmProviderResponse setDefault(UUID accountId, UUID providerId, boolean isDefault) {
        LlmProvider target = findOwned(accountId, providerId);
        String tag = resolvePrimaryTag(target.getTags());
        if (isDefault) {
            List<LlmProvider> all = llmProviderRepository.findAllByAccount(accountId);
            for (LlmProvider p : all) {
                if (!p.getProviderId().equals(providerId) && tagsMatch(p.getTags(), tag)) {
                    p.unsetDefault();
                    llmProviderRepository.save(p);
                }
            }
            llmProviderRepository.flush();
        }
        if (isDefault) target.setAsDefault();
        else target.unsetDefault();
        llmProviderRepository.save(target);
        return toResponse(target);
    }

    /** 测试 LLM 提供商连通性。 */
    public LlmTestResult testConnection(String providerType, String apiKey, String baseUrl) {
        var result = llmHttpClient.testConnection(providerType, apiKey, baseUrl);
        var models = result.models().stream()
                .map(m -> LlmTestResult.ModelInfo.builder()
                        .name(m.name()).contextLength(m.contextLength()).build())
                .toList();
        return LlmTestResult.builder()
                .success(result.success())
                .message(result.message())
                .models(models)
                .build();
    }

    /** 获取模型列表。 */
    public LlmModelListResult fetchModels(String providerType, String apiKey, String baseUrl) {
        var urlCheck = UrlValidator.validate(baseUrl);
        if (!urlCheck.valid()) {
            throw new DomainException(400, "error.llm.provider.url-check-failed", urlCheck.errorMessage());
        }
        List<String> models;
        try {
            models = llmHttpClient.fetchModels(providerType, apiKey, baseUrl);
        } catch (Exception e) {
            throw new DomainException(400, "error.llm.provider.models-fetch-failed", e.getMessage());
        }
        return LlmModelListResult.builder().models(models).build();
    }

    /** 查找当前用户拥有的 LLM 提供商。 */
    private LlmProvider findOwned(UUID accountId, UUID providerId) {
        LlmProvider provider = llmProviderRepository.findById(providerId)
                .orElseThrow(() -> ResourceNotFoundException.notFound("LLM 提供商", providerId));
        if (!provider.getAccountId().equals(accountId)) {
            throw ResourceNotFoundException.notFound("LLM 提供商", providerId);
        }
        return provider;
    }

    /** 转换为响应对象（apiKey 自动掩码，仅保留后 4 位）。 */
    private LlmProviderResponse toResponse(LlmProvider provider) {
        return LlmProviderResponse.builder()
                .providerId(provider.getProviderId())
                .name(provider.getName())
                .providerType(provider.getProviderType())
                .apiKey(maskApiKey(provider.getApiKey()))
                .baseUrl(provider.getBaseUrl())
                .model(provider.getModels())
                .active(provider.getActive())
                .isDefault(provider.getIsDefault())
                .capabilities(provider.getCapabilities())
                .contextLength(provider.getContextLength())
                .temperature(provider.getTemperature())
                .tags(provider.getTags())
                .createdAt(provider.getCreatedAt())
                .updatedAt(provider.getUpdatedAt())
                .build();
    }

    private static String maskApiKey(String apiKey) {
        if (apiKey == null || apiKey.length() <= 8) {
            return apiKey != null && apiKey.length() <= 6 ? apiKey : null;
        }
        return apiKey.substring(0, 4) + "****" + apiKey.substring(apiKey.length() - 4);
    }

    @Cacheable(cacheNames = "llmProviders", key = "#tag")
    public java.util.Optional<LlmProvider> findByTag(String tag) {
        return llmProviderRepository.findByTagsContains(tag).stream().findFirst();
    }

    /** 创建或更新系统级提供商。 */
    @CacheEvict(cacheNames = "llmProviders", allEntries = true)
    public void upsertSystemProvider(String providerType, String baseUrl, String models) {
        if (baseUrl != null && !baseUrl.isBlank()) {
            var urlCheck = UrlValidator.validate(baseUrl);
            if (!urlCheck.valid()) {
                throw new DomainException(400, "error.llm.provider.url-invalid", urlCheck.errorMessage());
            }
        }
        var existing = llmProviderRepository.findByTagsContains(providerType);
        LlmProvider provider = existing.stream().findFirst()
                .orElse(LlmProvider.builder()
                        .providerType(providerType)
                        .tags(providerType)
                        .name(providerType.equals("embedding") ? "默认 Embedding 服务" : "默认 Reranker 服务")
                        .active(true)
                        .build());
        if (existing.size() <= 1) provider.setAsDefault();
        provider.setBaseUrl(baseUrl);
        provider.setModels(models);
        llmProviderRepository.save(provider);
    }

    private static String resolvePrimaryTag(String tags) {
        if (tags == null || tags.isBlank()) return "";
        for (String t : tags.split(",")) {
            String s = t.strip();
            if (!s.isEmpty()) return s;
        }
        return "";
    }

    private static boolean tagsMatch(String tags, String targetTag) {
        if (targetTag == null || targetTag.isEmpty()) return true;
        if (tags == null || tags.isBlank()) return false;
        return List.of(tags.split(",")).stream().map(String::strip).anyMatch(targetTag::equals);
    }
}
