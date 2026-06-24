package com.icusu.sivan.infra.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.icusu.sivan.common.util.UrlValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * LLM HTTP 客户端 — 封装对 vLLM/Ollama/OpenAI 兼容 API 的 HTTP 调用。
 * <p>
 * 处理连通性测试、模型列表获取、上下文长度探测等基础设施通信。
 */
@Slf4j
@Component
public class LlmHttpClient {

    private static final int CONNECT_TIMEOUT = 5000;
    private static final int READ_TIMEOUT = 15000;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public LlmHttpClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT);
        factory.setReadTimeout(READ_TIMEOUT);
        this.restTemplate = new RestTemplate(factory);
    }

    public TestResult testConnection(String providerType, String apiKey, String baseUrl) {
        var urlCheck = UrlValidator.validate(baseUrl);
        if (!urlCheck.valid()) return TestResult.failure("URL 无效: " + urlCheck.errorMessage());
        try {
            var req = buildRequest(providerType, apiKey, baseUrl);
            ResponseEntity<String> response = restTemplate.exchange(req.url, HttpMethod.GET, req.entity, String.class);
            String body = response.getBody();
            if (body == null || body.isBlank()) return TestResult.failure("服务端返回空响应");
            return TestResult.success(parseContextLengths(body));
        } catch (Exception e) {
            log.warn("LLM 连接测试失败: {}", e.getMessage());
            return TestResult.failure("连接失败: " + e.getMessage());
        }
    }

    public List<String> fetchModels(String providerType, String apiKey, String baseUrl) {
        var urlCheck = UrlValidator.validate(baseUrl);
        if (!urlCheck.valid()) throw new IllegalArgumentException("URL 校验失败: " + urlCheck.errorMessage());
        List<String> models = new ArrayList<>();
        try {
            var req = buildRequest(providerType, apiKey, baseUrl);
            ResponseEntity<String> response = restTemplate.exchange(req.url, HttpMethod.GET, req.entity, String.class);
            String body = response.getBody();
            if (body == null || body.isBlank()) return models;
            JsonNode root = objectMapper.readTree(body);
            JsonNode data = root.get("data");
            if (data != null && data.isArray()) {
                for (JsonNode node : data) {
                    JsonNode id = node.get("id");
                    if (id != null) models.add(id.asText());
                }
            }
        } catch (Exception e) {
            log.warn("获取模型列表失败: {}", e.getMessage());
            throw new RuntimeException("获取模型列表失败: " + e.getMessage(), e);
        }
        return models;
    }

    public Integer resolveContextLength(String providerType, String apiKey, String baseUrl, String modelName) {
        if (modelName == null || modelName.isBlank()) return null;
        String primaryModel = modelName.split(",")[0].trim();
        if (apiKey == null || apiKey.isBlank() || baseUrl == null || baseUrl.isBlank()) return null;
        var urlCheck = UrlValidator.validate(baseUrl);
        if (!urlCheck.valid()) return null;
        try {
            var req = buildRequest(providerType, apiKey, baseUrl);
            ResponseEntity<String> response = restTemplate.exchange(req.url, HttpMethod.GET, req.entity, String.class);
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
        return null;
    }

    private ProviderRequest buildRequest(String providerType, String apiKey, String baseUrl) {
        var urlCheck = UrlValidator.validate(baseUrl);
        if (!urlCheck.valid()) throw new IllegalArgumentException("URL 无效: " + urlCheck.errorMessage());
        HttpHeaders headers = new HttpHeaders();
        boolean isAnthropic = "anthropic".equals(providerType);
        if (isAnthropic) {
            headers.set("x-api-key", apiKey);
            headers.set("anthropic-version", "2023-06-01");
        } else if (apiKey != null && !apiKey.isEmpty()) {
            headers.setBearerAuth(apiKey);
        }
        String url = normalizeV1Url(baseUrl) + "models";
        return new ProviderRequest(url, new HttpEntity<>(headers));
    }

    /**
     * 规范化 URL 路径，确保以 /v1/ 结尾。
     */
    private static String normalizeV1Url(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) return baseUrl;
        String url = baseUrl.strip();
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        if (url.endsWith("/v1")) return url + "/";
        if (url.contains("/v1/")) return url.endsWith("/") ? url : url + "/";
        return url + "/v1/";
    }

    private List<ModelInfo> parseContextLengths(String body) {
        List<ModelInfo> result = new ArrayList<>();
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
                    result.add(new ModelInfo(id.asText(), ctxLen));
                }
            }
        } catch (Exception e) {
            log.debug("解析模型列表失败: {}", e.getMessage());
        }
        return result;
    }

    private record ProviderRequest(String url, HttpEntity<String> entity) {
    }

    public record TestResult(boolean success, String message, List<ModelInfo> models) {
        public static TestResult success( List<ModelInfo> models) {
            return new TestResult(true, "连接成功", models);
        }
        public static TestResult failure(String message) {
            return new TestResult(false, message, List.of());
        }
    }

    public record ModelInfo(String name, Integer contextLength) {
    }
}
