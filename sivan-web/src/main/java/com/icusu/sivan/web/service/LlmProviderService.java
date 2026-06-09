package com.icusu.sivan.web.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.icusu.sivan.agent.model.ModelCapabilityRegistry;
import com.icusu.sivan.common.exception.DomainException;
import com.icusu.sivan.common.exception.ResourceNotFoundException;
import com.icusu.sivan.domain.model.LlmProvider;
import com.icusu.sivan.domain.model.ILlmProviderRepository;
import com.icusu.sivan.web.model.dto.CreateLlmProviderRequest;
import com.icusu.sivan.web.model.dto.UpdateLlmProviderRequest;
import com.icusu.sivan.web.model.dto.LlmModelListResult;
import com.icusu.sivan.web.model.dto.LlmProviderResponse;
import com.icusu.sivan.web.model.dto.LlmTestResult;
import com.icusu.sivan.web.model.dto.ModelCapabilityInfo;
import com.icusu.sivan.common.util.UrlValidator;
import com.icusu.sivan.agent.model.ModelRouter;
import com.icusu.sivan.domain.model.ModelCapability;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
/** LLM 提供商管理服务，管理模型提供商配置、连通性测试与模型列表获取。 */
@Service
public class LlmProviderService {

    private final ILlmProviderRepository llmProviderRepository;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;
    private final ModelRouter modelRouter;
    private final ModelCapabilityRegistry capabilityRegistry;

    /** 连接超时（毫秒）。 */
    private static final int CONNECT_TIMEOUT = 5000;
    /** 读取超时（毫秒）。 */
    private static final int READ_TIMEOUT = 15000;

    public LlmProviderService(ILlmProviderRepository llmProviderRepository,
                              ObjectMapper objectMapper,
                              ModelRouter modelRouter, ModelCapabilityRegistry capabilityRegistry) {
        this.llmProviderRepository = llmProviderRepository;
        this.objectMapper = objectMapper;
        this.modelRouter = modelRouter;
        this.capabilityRegistry = capabilityRegistry;
        this.restTemplate = createRestTemplate();
    }

    /** 创建带超时配置的 RestTemplate。 */
    private static RestTemplate createRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT);
        factory.setReadTimeout(READ_TIMEOUT);
        return new RestTemplate(factory);
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

    /** 根据模型名和 providerType 推断能力编码列表（模型名优先）。 */
    public List<String> inferCapabilities(String modelName, String providerType) {
        return capabilityRegistry.infer(modelName, providerType).stream()
                .map(ModelCapability::getCode)
                .toList();
    }

    /** 创建 LLM 提供商。capabilities 仅在用途标签包含 chat 时需要。 */
    @CacheEvict(cacheNames = "llmProviders", allEntries = true)
    public LlmProviderResponse create(UUID accountId, CreateLlmProviderRequest request) {
        String tags = request.getTags();
        boolean needsChat = tags != null && tags.contains("chat");
        String caps = request.getCapabilities();
        if (needsChat && (caps == null || caps.isBlank())) {
            throw new DomainException(400, "error.llm.provider.capabilities-required");
        }

        // baseUrl 入库前校验（协议白名单、字符安全）
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

        // 用户指定了上下文长度则直接用，否则自动检测
        Integer ctxLen = request.getContextLength() != null
                ? request.getContextLength()
                : resolveContextLength(request.getProviderType(), request.getApiKey(),
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

        // baseUrl 入库前校验（协议白名单、字符安全）
        if (request.getBaseUrl() != null && !request.getBaseUrl().isBlank()) {
            var urlCheck = UrlValidator.validate(request.getBaseUrl());
            if (!urlCheck.valid()) {
                throw new DomainException(400, "error.llm.provider.url-invalid", urlCheck.errorMessage());
            }
        }

        provider.updateFrom(request.getName(), request.getProviderType(), request.getApiKey(),
                request.getBaseUrl(), request.getModel(), request.getCapabilities(), request.getActive(),
                request.getTemperature(), request.getContextLength());
        if (request.getTags() != null) {
            provider.setTags(request.getTags());
        }
        if (request.getIsDefault() != null) {
            setDefault(accountId, providerId, request.getIsDefault());
        }

        llmProviderRepository.save(provider);

        // 用户未指定上下文长度时，若 baseUrl 或 model 变更则自动检测
        if (request.getContextLength() == null
                && (request.getBaseUrl() != null || request.getModel() != null)) {
            String apiKey = request.getApiKey() != null ? request.getApiKey() : provider.getApiKey();
            String baseUrl = request.getBaseUrl() != null ? request.getBaseUrl() : provider.getBaseUrl();
            String model = request.getModel() != null ? request.getModel() : provider.getModels();
            Integer ctxLen = resolveContextLength(provider.getProviderType(), apiKey, baseUrl, model);
            provider.setContextLength(ctxLen);
            llmProviderRepository.save(provider);
        }

        // 清除模型路由缓存，使新配置生效
        modelRouter.evictCache(providerId);

        return toResponse(provider);
    }

    /** 删除 LLM 提供商。 */
    @CacheEvict(cacheNames = "llmProviders", allEntries = true)
    public void delete(UUID accountId, UUID providerId) {
        LlmProvider provider = findOwned(accountId, providerId);
        llmProviderRepository.delete(provider.getProviderId());
    }

    /** 设置 LLM 提供商为默认。非对话标签的提供商不能设为默认。 */
    @Transactional
    public LlmProviderResponse setDefault(UUID accountId, UUID providerId, boolean isDefault) {
        LlmProvider target = findOwned(accountId, providerId);
        String tag = resolvePrimaryTag(target.getTags());

        if (isDefault) {
            // 仅清除同一 tag 组内的默认标记
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

    /** 取 tags 中第一个有意义的标签作为分组依据。 */
    private static String resolvePrimaryTag(String tags) {
        if (tags == null || tags.isBlank()) return "";
        for (String t : tags.split(",")) {
            String s = t.strip();
            if (!s.isEmpty()) return s;
        }
        return "";
    }

    /** 判断提供商的 tags 是否包含目标 tag。 */
    private static boolean tagsMatch(String tags, String targetTag) {
        if (targetTag == null || targetTag.isEmpty()) return true;
        if (tags == null || tags.isBlank()) return false;
        return List.of(tags.split(",")).stream().map(String::strip).anyMatch(targetTag::equals);
    }

    /**
     * 测试 LLM 提供商连通性。
     */
    public LlmTestResult testConnection(String providerType, String apiKey, String baseUrl) {
        // baseUrl 基础校验（协议白名单、字符安全）
        var urlCheck = UrlValidator.validate(baseUrl);
        if (!urlCheck.valid()) {
            return LlmTestResult.builder()
                    .success(false)
                    .message("URL 无效: " + urlCheck.errorMessage())
                    .build();
        }

        try {
            var req = buildProviderRequest(providerType, apiKey, baseUrl);
            ResponseEntity<String> response = restTemplate.exchange(req.url(), HttpMethod.GET, req.entity(), String.class);
            String body = response.getBody();
            if (body == null || body.isBlank()) {
                return LlmTestResult.builder().success(false).message("服务端返回空响应").build();
            }

            List<LlmTestResult.ModelInfo> models = new ArrayList<>();
            try {
                JsonNode root = objectMapper.readTree(body);
                JsonNode data = root.get("data");
                if (data != null && data.isArray()) {
                    for (JsonNode node : data) {
                        JsonNode id = node.get("id");
                        if (id == null) continue;
                        Integer ctxLen = null;
                        if (node.has("max_model_len")) ctxLen = node.get("max_model_len").asInt();
                        else if (node.has("context_length")) ctxLen = node.get("context_length").asInt();
                        else if (node.has("max_context_length")) ctxLen = node.get("max_context_length").asInt();
                        models.add(LlmTestResult.ModelInfo.builder()
                                .name(id.asText()).contextLength(ctxLen).build());
                    }
                }
            } catch (Exception e) {
                log.debug("解析模型列表失败: {}", e.getMessage());
            }

            return LlmTestResult.builder()
                    .success(true)
                    .message("连接成功")
                    .models(models)
                    .build();
        } catch (Exception e) {
            log.warn("LLM 连接测试失败: {}", e.getMessage());
            return LlmTestResult.builder()
                    .success(false)
                    .message("连接失败: " + e.getMessage())
                    .build();
        }
    }

    /**
     * 获取 LLM 提供商可用的模型列表。
     */
    public LlmModelListResult fetchModels(String providerType, String apiKey, String baseUrl) {
        // baseUrl 基础校验（协议白名单、字符安全）
        var urlCheck = UrlValidator.validate(baseUrl);
        if (!urlCheck.valid()) {
            throw new DomainException(400, "error.llm.provider.url-check-failed", urlCheck.errorMessage());
        }

        List<String> models = new ArrayList<>();
        try {
            var req = buildProviderRequest(providerType, apiKey, baseUrl);
            ResponseEntity<String> response = restTemplate.exchange(req.url(), HttpMethod.GET, req.entity(), String.class);
            String body = response.getBody();
            if (body == null || body.isBlank()) {
                return new LlmModelListResult(models); // 无模型不影响主流程
            }

            JsonNode root = objectMapper.readTree(body);
            JsonNode data = root.get("data");
            if (data != null && data.isArray()) {
                for (JsonNode node : data) {
                    JsonNode id = node.get("id");
                    if (id != null) {
                        models.add(id.asText());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("获取模型列表失败: {}", e.getMessage());
            throw new DomainException(400, "error.llm.provider.models-fetch-failed", e.getMessage());
        }

        return LlmModelListResult.builder().models(models).build();
    }

    /** 构建 LLM 提供商 HTTP 请求的 Header 和 URL。不同提供商的鉴权路径各异。 */
    private ProviderRequest buildProviderRequest(String providerType, String apiKey, String baseUrl) {
        // SSRF 防护：所有外部请求 URL 须经 DNS 解析 + 私有地址白名单校验
        var urlCheck = UrlValidator.validate(baseUrl);
        if (!urlCheck.valid()) {
            throw new DomainException(400, "error.llm.provider.url-invalid", urlCheck.errorMessage());
        }

        HttpHeaders headers = new HttpHeaders();
        boolean isAnthropic = "anthropic".equals(providerType);
        if (isAnthropic) {
            headers.set("x-api-key", apiKey);
            headers.set("anthropic-version", "2023-06-01");
        } else if (apiKey != null && !apiKey.isEmpty()) {
            headers.setBearerAuth(apiKey);
        }
        String url = com.icusu.sivan.agent.model.OpenAiModel.normalizeV1Url(baseUrl) + "models";
        return new ProviderRequest(url, new HttpEntity<>(headers));
    }

    private record ProviderRequest(String url, HttpEntity<String> entity) {}

    /**
     * 查询 LLM 模型的最大上下文长度（tokens），从 /v1/models 响应解析。
     * 无法获取时返回 null，不猜值。
     */
    private Integer resolveContextLength(String providerType, String apiKey, String baseUrl, String modelName) {
        if (modelName == null || modelName.isBlank()) return null;
        String primaryModel = modelName.split(",")[0].trim();

        if (apiKey != null && !apiKey.isBlank() && baseUrl != null && !baseUrl.isBlank()) {
            // baseUrl 基础校验（协议白名单、字符安全）
            var urlCheck = UrlValidator.validate(baseUrl);
            if (!urlCheck.valid()) {
                log.debug("resolveContextLength 跳过 — URL 校验失败: {}", urlCheck.errorMessage());
                return null;
            }

            try {
                var req = buildProviderRequest(providerType, apiKey, baseUrl);
                ResponseEntity<String> response = restTemplate.exchange(req.url(), HttpMethod.GET, req.entity(), String.class);
                String body = response.getBody();
                if (body == null || body.isBlank()) return null;
                JsonNode root = objectMapper.readTree(body);
                JsonNode data = root.get("data");
                if (data != null && data.isArray()) {
                    for (JsonNode node : data) {
                        JsonNode id = node.get("id");
                        if (id != null && primaryModel.equals(id.asText())) {
                            if (node.has("max_model_len")) return node.get("max_model_len").asInt();
                            if (node.has("context_length")) return node.get("context_length").asInt();
                            if (node.has("max_context_length")) return node.get("max_context_length").asInt();
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("从 /v1/models 获取 contextLength 失败: {}", e.getMessage());
            }
        }
        return null;
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

    /** 掩码 API key，保留前 4 + 后 4 位，中间用 **** 替换。 */
    private static String maskApiKey(String apiKey) {
        if (apiKey == null || apiKey.length() <= 8) {
            return apiKey != null && apiKey.length() <= 6 ? apiKey : null;
        }
        return apiKey.substring(0, 4) + "****" + apiKey.substring(apiKey.length() - 4);
    }

    /** 按 tag 查找 LLM 提供商（取第一条匹配）。 */
    @Cacheable(cacheNames = "llmProviders", key = "#tag")
    public java.util.Optional<LlmProvider> findByTag(String tag) {
        return llmProviderRepository.findByTagsContains(tag).stream().findFirst();
    }

    /** 创建或更新系统级提供商。 */
    @CacheEvict(cacheNames = "llmProviders", allEntries = true)
    public void upsertSystemProvider(String providerType, String baseUrl, String models) {
        // baseUrl 入库前校验（协议白名单、字符安全）
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
        // 如果只有一个 embedding/reranker 提供商，自动设为默认
        if (existing.size() <= 1) {
            provider.setAsDefault();
        }
        provider.setBaseUrl(baseUrl);
        provider.setModels(models);
        llmProviderRepository.save(provider);
    }
}
