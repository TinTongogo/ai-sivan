package com.icusu.sivan.infra.knowledge;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.icusu.sivan.common.util.UrlValidator;
import com.icusu.sivan.domain.model.ILlmProviderRepository;
import com.icusu.sivan.domain.model.LlmProvider;
import com.icusu.sivan.domain.model.ModelCapability;
import com.icusu.sivan.domain.shared.port.IEmbeddingService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;

/**
 * Embedding 服务，支持文本和图片多模态向量化。
 * URL 和模型名称从 llm_providers 中 tags 包含 'embedding' 的记录读取（取第一条）。
 * 未配置时所有 public 方法静默返回空结果，不抛出异常，确保主流程不受影响。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmbeddingService implements IEmbeddingService {

    private final ILlmProviderRepository llmProviderRepository;
    private RestClient restClient;

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(15);

    private RestClient getRestClient() {
        if (restClient == null) {
            var httpClient = HttpClient.newBuilder()
                    .connectTimeout(CONNECT_TIMEOUT)
                    .build();
            var factory = new JdkClientHttpRequestFactory(httpClient);
            factory.setReadTimeout(READ_TIMEOUT);
            restClient = RestClient.builder()
                    .requestFactory(factory)
                    .build();
        }
        return restClient;
    }

    /**
     * 检查 Embedding 服务是否已配置。未配置时系统功能正常降级，不抛出异常。
     */
    public boolean isAvailable() {
        return llmProviderRepository.findByTagsContains("embedding").stream()
                .findFirst()
                .filter(p -> p.getBaseUrl() != null && !p.getBaseUrl().isBlank())
                .isPresent();
    }

    private LlmProvider getProvider() {
        var providers = llmProviderRepository.findByTagsContains("embedding").stream()
                .filter(p -> p.getBaseUrl() != null && !p.getBaseUrl().isBlank())
                .toList();
        if (providers.isEmpty()) return null;
        // 优先取 isEmbedDefault=true 的 provider
        return providers.stream()
                .filter(p -> Boolean.TRUE.equals(p.getIsEmbedDefault()))
                .findFirst()
                .orElse(providers.getFirst());
    }

    private String getModel(LlmProvider p) {
        return p != null && p.getPrimaryModelName() != null && !p.getPrimaryModelName().isBlank()
                ? p.getPrimaryModelName() : null;
    }

    private String getApiKey(LlmProvider p) {
        return p != null ? p.getApiKey() : null;
    }

    // ====== URL 构建 ======

    // ====== URL 构建（package-private 方便测试） ======

    String buildEmbedUrl(LlmProvider p) {
        if (p == null) return null;
        String baseUrl = p.getBaseUrl();
        var check = UrlValidator.validatePrivateAccess(baseUrl);
        if (!check.valid()) {
            log.warn("Embedding URL 校验失败: {}", check.errorMessage());
            return null;
        }
        return buildOpenAiEmbedUrl(check.sanitizedUrl());
    }

    String buildOllamaEmbedUrl(LlmProvider p) {
        if (p == null) return null;
        String baseUrl = p.getBaseUrl();
        var check = UrlValidator.validatePrivateAccess(baseUrl);
        if (!check.valid()) {
            log.warn("Ollama Embedding URL 校验失败: {}", check.errorMessage());
            return null;
        }
        return buildOllamaEmbedUrl(check.sanitizedUrl());
    }

    /**
     * 构建 OpenAI 兼容 Embedding URL（/v1/embeddings）。
     */
    static String buildOpenAiEmbedUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) return null;
        String base = baseUrl.strip().replaceAll("/+$", "");
        if (base.contains("/api/embed") || base.contains("/v1/embeddings")) {
            return base;
        }
        if (base.endsWith("/v1") || base.endsWith("/v1/")) {
            return base.replaceAll("/+$", "") + "/embeddings";
        }
        return base + "/v1/embeddings";
    }

    /**
     * 构建 Ollama 原生 Embedding URL（/api/embed，用于多模态图文向量化）。
     */
    static String buildOllamaEmbedUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) return null;
        String base = baseUrl.strip().replaceAll("/+$", "");
        if (base.contains("/api/embed")) return base;
        return base + "/api/embed";
    }

    // ====== Public API ======

    /**
     * 将单条文本转为向量。服务未配置时返回 null。
     */
    public float[] embed(String text) {
        if (!isAvailable()) {
            log.warn("Embedding 服务未配置，返回 null");
            return null;
        }
        List<float[]> results = embedMultimodal(List.of(new EmbeddingInput(text, null)));
        return results.isEmpty() ? null : results.getFirst();
    }

    /**
     * 将文本 + 图片转为向量（Qwen3-VL-Embedding 多模态）。
     * imageBase64 为 data URI 格式：data:image/png;base64,...
     * 服务未配置时返回 null。
     */
    public float[] embedWithImage(String text, String imageBase64) {
        if (!isAvailable()) {
            log.warn("Embedding 服务未配置，返回 null");
            return null;
        }
        List<float[]> results = embedMultimodal(List.of(new EmbeddingInput(text, imageBase64)));
        return results.isEmpty() ? null : results.getFirst();
    }

    /**
     * 批量将文本转为向量。单次 HTTP 请求完成批量 embedding，减少调用次数。
     */
    public List<float[]> embedBatch(List<String> texts) {
        if (!isAvailable()) {
            log.warn("Embedding 服务未配置，返回空列表");
            return List.of();
        }
        List<EmbeddingInput> inputs = texts.stream().map(t -> new EmbeddingInput(t, null)).toList();
        return embedMultimodal(inputs);
    }

    /**
     * 批量多模态 embedding，支持纯文本和图文混合输入。
     * 服务未配置时返回空列表。
     */
    public List<float[]> embedMultimodal(List<EmbeddingInput> inputs) {
        if (!isAvailable()) {
            log.warn("Embedding 服务未配置，返回空列表");
            return List.of();
        }

        LlmProvider p = getProvider();
        if (p == null) {
            log.warn("Embedding 模型名称未配置，返回空列表");
            return List.of();
        }

        String model = getModel(p);
        if (model == null) {
            log.warn("Embedding 模型名称未配置，返回空列表");
            return List.of();
        }
        String apiKey = getApiKey(p);

        boolean hasImages = inputs.stream().anyMatch(in -> in.getImage() != null);
        if (hasImages) {
            // 多模态向量化：仅当 provider 明确声明 multimodel_embed 能力时才发送图片
            if (p.supportsCapability(ModelCapability.MULTIMODAL_EMBED.getCode())) {
                String url = buildOllamaEmbedUrl(p);
                log.debug("Embedding 请求(buildOllamaEmbedUrl): url={}, model={}, hasImages={}", url, model, hasImages);
                return embedOllamaNative(inputs, model, buildOllamaEmbedUrl(p), apiKey);
            } else {
                log.debug("Provider {} 不支持多模态向量化，降级为 text-only (model={})", p.getName(), model);
                List<EmbeddingInput> textInputs = inputs.stream()
                        .map(in -> new EmbeddingInput(in.getText(), null))
                        .toList();
                return embedOpenAiCompatible(textInputs, model, buildEmbedUrl(p), apiKey);
            }
        } else {
            String url = buildEmbedUrl(p);
            log.debug("Embedding 请求(embedOpenAiCompatible): url={}, model={}, hasImages={}", url, model, hasImages);
            return embedOpenAiCompatible(inputs, model, buildEmbedUrl(p), apiKey);
        }
    }

    // ====== OpenAI 兼容路径（/v1/embeddings） ======

    private List<float[]> embedOpenAiCompatible(List<EmbeddingInput> inputs, String model, String url, String apiKey) {
        if (url == null) {
            log.warn("Embedding URL 未配置，返回空列表");
            return List.of();
        }

        EmbeddingRequest request = new EmbeddingRequest();
        request.setModel(model);
        request.setInput(inputs.stream().map(EmbeddingInput::getText).toList());

        EmbeddingResponse response = getRestClient().post()
                .uri(url)
                .headers(headers -> {
                    if (apiKey != null && !apiKey.isBlank()) {
                        headers.setBearerAuth(apiKey);
                    }
                })
                .body(request)
                .retrieve()
                .body(EmbeddingResponse.class);

        if (response == null || response.getData() == null || response.getData().isEmpty()) {
            log.warn("Embedding 服务返回空结果(url={}, model={})，请检查模型配置或输入内容", url, model);
            return List.of();
        }

        return response.getData().stream()
                .map(d -> {
                    List<Double> embedding = d.getEmbedding();
                    float[] result = new float[embedding.size()];
                    for (int i = 0; i < embedding.size(); i++) {
                        result[i] = embedding.get(i).floatValue();
                    }
                    return result;
                })
                .toList();
    }

    // ====== Ollama 原生路径（/api/embed，支持多模态） ======

    private List<float[]> embedOllamaNative(List<EmbeddingInput> inputs, String model, String url, String apiKey) {

        if (url == null) {
            log.warn("Ollama Embedding URL 未配置，返回空列表");
            return List.of();
        }
        OllamaEmbedRequest request = new OllamaEmbedRequest();
        request.setModel(model);
        request.setInput(inputs.stream()
                .map(in -> {
                    if (in.getImage() != null) {
                        return new MultimodalInput(in.getText(), in.getImage());
                    }
                    return in.getText();
                })
                .toList());

        OllamaEmbedResponse response = getRestClient().post()
                .uri(url)
                .headers(headers -> {
                    if (apiKey != null && !apiKey.isBlank()) {
                        headers.setBearerAuth(apiKey);
                    }
                })
                .body(request)
                .retrieve()
                .body(OllamaEmbedResponse.class);

        if (response == null || response.getEmbeddings() == null || response.getEmbeddings().isEmpty()) {
            log.warn("Ollama Embedding 服务返回空结果(url={}, model={})，请检查模型配置或输入内容", url, model);
            return List.of();
        }

        return response.getEmbeddings().stream()
                .map(emb -> {
                    float[] result = new float[emb.size()];
                    for (int i = 0; i < emb.size(); i++) {
                        result[i] = emb.get(i).floatValue();
                    }
                    return result;
                })
                .toList();
    }

    // ====== 内部 POJO ======

    /**
     * 多模态 embedding 输入项：文本可选，图片可选（base64 data URI）。
     */
    @Data
    @RequiredArgsConstructor
    public static class EmbeddingInput {
        private final String text;
        private final String image;
    }

    /**
     * 多模态输入的 POJO，image 字段非空时才序列化。
     */
    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private static class MultimodalInput {
        private String text;
        private String image;

        MultimodalInput(String text, String image) {
            this.text = text;
            this.image = image;
        }
    }

    // ---- OpenAI 兼容格式 ----

    @Data
    private static class EmbeddingRequest {
        private String model;
        private Object input;
    }

    @Data
    private static class EmbeddingResponse {
        private List<EmbeddingData> data;
        private String model;
        private Usage usage;
    }

    @Data
    private static class EmbeddingData {
        @JsonProperty("embedding")
        private List<Double> embedding;
        private Integer index;
    }

    // ---- Ollama 原生格式（/api/embed） ----

    @Data
    private static class OllamaEmbedRequest {
        private String model;
        private Object input;
    }

    @Data
    private static class OllamaEmbedResponse {
        @JsonProperty("embeddings")
        private List<List<Double>> embeddings;
        private String model;
    }

    // ---- 通用 ----

    @Data
    private static class Usage {
        @JsonProperty("total_tokens")
        private Integer totalTokens;
    }
}
