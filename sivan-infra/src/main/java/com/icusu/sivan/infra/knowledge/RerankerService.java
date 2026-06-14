package com.icusu.sivan.infra.knowledge;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.icusu.sivan.common.util.UrlValidator;
import com.icusu.sivan.domain.model.LlmProvider;
import com.icusu.sivan.domain.model.ILlmProviderRepository;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 重排序服务，对查询和候选文档进行相关性重排序。
 * URL 和模型名称从 llm_providers 中 tags 包含 'reranker' 的记录读取（取第一条）。
 * 未配置时所有 public 方法静默返回空结果，不抛出异常，确保主流程不受影响。
 * <p>
 * 自动适配两种 reranker API 格式：
 * <ul>
 *   <li><b>Ollama</b>（默认）：POST /api/rerank，query/documents 为纯字符串，响应 results</li>
 *   <li><b>vLLM</b>（URL 含 /v1/ 时）：POST /v1/score，query/documents 为对象，响应 data</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RerankerService {

    private final ILlmProviderRepository llmProviderRepository;
    private RestClient restClient;

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(15);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** Prompt 重排序最多处理的文档数，避免超出上下文窗口。 */
    private static final int PROMPT_MAX_DOCS = 20;

    private RestClient getRestClient() {
        if (restClient == null) {
            var httpClient = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(CONNECT_TIMEOUT)
                    .build();
            var factory = new org.springframework.http.client.JdkClientHttpRequestFactory(httpClient);
            factory.setReadTimeout(READ_TIMEOUT);
            restClient = RestClient.builder()
                    .requestFactory(factory)
                    .build();
        }
        return restClient;
    }

    /** 检查 Reranker 服务是否已配置。未配置时系统功能正常降级，不抛出异常。 */
    public boolean isAvailable() {
        return llmProviderRepository.findByTagsContains("reranker").stream()
                .findFirst()
                .filter(p -> p.getBaseUrl() != null && !p.getBaseUrl().isBlank())
                .isPresent();
    }

    private LlmProvider getProvider() {
        var providers = llmProviderRepository.findByTagsContains("reranker").stream()
                .filter(p -> p.getBaseUrl() != null && !p.getBaseUrl().isBlank())
                .toList();
        if (providers.isEmpty()) return null;
        // 优先取 isRerankDefault=true 的 provider
        return providers.stream()
                .filter(p -> Boolean.TRUE.equals(p.getIsRerankDefault()))
                .findFirst()
                .orElse(providers.getFirst());
    }

    private String getUrl() {
        LlmProvider p = getProvider();
        if (p == null) return null;
        String baseUrl = p.getBaseUrl();
        var check = UrlValidator.validatePrivateAccess(baseUrl);
        if (!check.valid()) {
            log.warn("Reranker URL 校验失败: {}", check.errorMessage());
            return null;
        }
        return normalizeRerankUrl(check.sanitizedUrl());
    }

    /** 判断是否为 vLLM 类服务（URL 含 /v1/ 则为 OpenAI 兼容格式）。 */
    private boolean isVllmStyle(String baseUrl) {
        return baseUrl != null && baseUrl.contains("/v1/");
    }

    /** URL 规范化：vLLM 风格追加 /v1/score，否则追加 /api/rerank（Ollama）。 */
    static String normalizeRerankUrl(String url) {
        if (url == null || url.isBlank()) return url;
        url = url.strip().replaceAll("/+$", "");
        if (url.contains("/v1/") || url.endsWith("/rerank") || url.endsWith("/score")) {
            return url;
        }
        return url + "/api/rerank";
    }

    private String getModel() {
        LlmProvider p = getProvider();
        return p != null && p.getPrimaryModelName() != null && !p.getPrimaryModelName().isBlank()
                ? p.getPrimaryModelName() : null;
    }

    private String getApiKey() {
        LlmProvider p = getProvider();
        return p != null ? p.getApiKey() : null;
    }

    /**
     * 对查询和文本列表进行重排序，返回按相关性降序排列的索引。
     * 服务未配置时返回空列表。
     */
    public List<Integer> rerank(String query, List<String> texts) {
        if (!isAvailable()) {
            log.warn("Reranker 服务未配置，返回空列表");
            return List.of();
        }
        String model = getModel();
        if (model == null) {
            log.warn("Reranker 模型名称未配置，返回空列表");
            return List.of();
        }
        String url = getUrl();
        if (url == null) {
            log.warn("Reranker URL 未配置，返回空列表");
            return List.of();
        }

        // 1) 优先使用专用端点（vLLM / Ollama ≥0.5.0）
        LlmProvider provider = getProvider();
        boolean vllm = isVllmStyle(provider != null ? provider.getBaseUrl() : null);
        Object requestBody = buildRequest(model, query, texts, vllm);
        String apiKey = getApiKey();

        try {
            RerankResponse response = getRestClient().post()
                    .uri(url)
                    .headers(headers -> {
                        if (apiKey != null && !apiKey.isBlank()) {
                            headers.setBearerAuth(apiKey);
                        }
                    })
                    .body(requestBody)
                    .retrieve()
                    .body(RerankResponse.class);

            if (response != null && response.getItems() != null && !response.getItems().isEmpty()) {
                return response.getItems().stream()
                        .sorted((a, b) -> Double.compare(b.getRelevanceScore(), a.getRelevanceScore()))
                        .map(RerankResult::getIndex)
                        .toList();
            }
        } catch (Exception e) {
            log.warn("专用 Reranker 端点失败，尝试 Prompt 重排序: {}", e.getMessage());
        }

        // 2) 兜底：基于 chat LLM 的 prompt 重排序（兼容 Ollama 0.24.0 等无专用端点的场景）
        List<Integer> promptResult = promptBasedRerank(query, texts);
        if (promptResult != null && !promptResult.isEmpty()) {
            log.info("Prompt 重排序成功，文档数: {}", promptResult.size());
            return promptResult;
        }

        log.warn("重排序失败（无可用 Reranker），返回空列表");
        return List.of();
    }

    /** 根据后端类型构建请求体。 */
    private Object buildRequest(String model, String query, List<String> texts, boolean vllm) {
        if (vllm) {
            return new VllmRerankRequest(model, query, texts);
        }
        return new OllamaRerankRequest(model, query, texts);
    }

    // ====== Prompt 重排序（兜底方案，兼容 Ollama 0.24.0 等无专用端点的场景） ======

    /**
     * 基于 chat LLM 的 prompt 重排序。使用 chat 提供商批量评分，
     * 适用于无专用 reranker 端点时的降级。
     */
    /** 判断 URL 是否为本地地址。 */
    private static boolean isLocalUrl(String url) {
        return url != null && (url.contains("localhost") || url.contains("127.0.0.1") || url.contains("0.0.0.0"));
    }

    /** 判断提供商是否为 OpenAI 兼容格式（含 providerType 和 URL 双重判定）。 */
    private static boolean isOpenaiCompatible(LlmProvider provider, String baseUrl) {
        if (baseUrl != null && baseUrl.contains("/v1/")) return true;
        if (provider != null && provider.getProviderType() != null) {
            String type = provider.getProviderType();
            return "openai".equals(type) || "azure".equals(type) || "openai-compatible".equals(type);
        }
        return false;
    }

    private List<Integer> promptBasedRerank(String query, List<String> texts) {
        // 优先选本地 Ollama（免费/低延迟），兜底选任意 chat 提供商
        LlmProvider chatProvider = llmProviderRepository.findByTagsContains("chat").stream()
                .filter(p -> p.getBaseUrl() != null && !p.getBaseUrl().isBlank())
                .filter(p -> isLocalUrl(p.getBaseUrl()))
                .findFirst()
                .orElseGet(() -> llmProviderRepository.findByTagsContains("chat").stream()
                        .filter(p -> p.getBaseUrl() != null && !p.getBaseUrl().isBlank())
                        .findFirst()
                        .orElse(null));
        if (chatProvider == null) {
            log.warn("Prompt 重排序失败：未配置 chat 提供商");
            return null;
        }

        String model = chatProvider.getPrimaryModelName();
        if (model == null || model.isBlank()) return null;

        String baseUrl = chatProvider.getBaseUrl().strip().replaceAll("/+$", "");
        boolean openaiStyle = isOpenaiCompatible(chatProvider, baseUrl);

        int maxDocs = Math.min(texts.size(), PROMPT_MAX_DOCS);
        String prompt = buildScoringPrompt(query, texts, maxDocs);
        String response = callLlm(chatProvider, baseUrl, openaiStyle, model, prompt);
        if (response == null || response.isBlank()) return null;

        // 优先尝试 JSON 数组解析，兜底按行解析
        List<Integer> result = parseJsonScores(response, maxDocs);
        if (result != null) return result;
        return parseLineScores(response, maxDocs);
    }

    /** 构建批量评分 prompt。 */
    private static String buildScoringPrompt(String query, List<String> texts, int maxDocs) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个文本相关性评分助手。为以下每个文档与查询的相关性打分，0-10分（10最相关）。\n");
        sb.append("只输出一个 JSON 数组，不要额外说明。\n\n");
        sb.append("查询：").append(query).append("\n\n");
        sb.append("文档列表：\n");
        for (int i = 0; i < maxDocs; i++) {
            String text = texts.get(i);
            if (text.length() > 500) text = text.substring(0, 500);
            sb.append(i).append(": ").append(text).append("\n");
        }
        sb.append("\n输出格式：[分数0, 分数1, 分数2, ...]");
        return sb.toString();
    }

    /** 调用 LLM 获取文本响应。 */
    private String callLlm(LlmProvider provider, String baseUrl, boolean openaiStyle, String model, String prompt) {
        try {
            String apiKey = provider.getApiKey();
            if (openaiStyle) {
                String url = baseUrl.contains("/v1/")
                        ? baseUrl + "/chat/completions"
                        : baseUrl + "/v1/chat/completions";
                Map<String, Object> body = new java.util.LinkedHashMap<>();
                body.put("model", model);
                body.put("messages", List.of(Map.of("role", "user", "content", prompt)));
                body.put("max_tokens", 512);
                body.put("temperature", 0.1);
                body.put("stream", false);

                JsonNode resp = getRestClient().post()
                        .uri(url)
                        .headers(h -> { if (apiKey != null && !apiKey.isBlank()) h.setBearerAuth(apiKey); })
                        .body(body)
                        .retrieve()
                        .body(JsonNode.class);
                if (resp != null) {
                    JsonNode content = resp.at("/choices/0/message/content");
                    return content.isMissingNode() ? null : content.asText();
                }
            } else {
                String url = baseUrl + "/api/generate";
                Map<String, Object> body = new java.util.LinkedHashMap<>();
                body.put("model", model);
                body.put("prompt", prompt);
                body.put("stream", false);

                JsonNode resp = getRestClient().post()
                        .uri(url)
                        .body(body)
                        .retrieve()
                        .body(JsonNode.class);
                if (resp != null) {
                    JsonNode responseField = resp.get("response");
                    return responseField != null ? responseField.asText() : null;
                }
            }
        } catch (Exception e) {
            log.warn("Prompt 重排序调用 LLM 失败: {}", e.getMessage());
        }
        return null;
    }

    /** 从 LLM 响应中解析 JSON 数组格式的分数。 */
    private List<Integer> parseJsonScores(String response, int maxDocs) {
        try {
            String cleaned = response.replaceAll("```(?:json)?", "").trim();
            int startIdx = cleaned.indexOf('[');
            int endIdx = cleaned.lastIndexOf(']');
            if (startIdx >= 0 && endIdx > startIdx) {
                String json = cleaned.substring(startIdx, endIdx + 1);
                JsonNode arr = MAPPER.readTree(json);
                if (arr.isArray() && arr.size() >= maxDocs) {
                    double[] scores = new double[maxDocs];
                    for (int i = 0; i < maxDocs; i++) scores[i] = arr.get(i).asDouble();
                    List<Integer> indices = new ArrayList<>(maxDocs);
                    for (int i = 0; i < maxDocs; i++) indices.add(i);
                    indices.sort((a, b) -> Double.compare(scores[b], scores[a]));
                    return indices;
                }
            }
        } catch (Exception e) {
            log.warn("JSON 分数解析失败: {}", e.getMessage());
        }
        return null;
    }

    /** 从 LLM 响应中按行解析分数（JSON 解析失败的兜底方案）。 */
    private List<Integer> parseLineScores(String response, int maxDocs) {
        try {
            String cleaned = response.replaceAll("```(?:json)?", "").trim();
            String[] lines = cleaned.split("\n");
            double[] scores = new double[maxDocs];
            boolean[] parsed = new boolean[maxDocs];
            int slot = 0;

            for (String line : lines) {
                if (slot >= maxDocs) break;
                line = line.trim();
                if (line.isEmpty()) continue;
                // 从行中提取数字
                String numStr = line.replaceAll("[^0-9.]", "").trim();
                if (numStr.isEmpty()) continue;
                try {
                    scores[slot] = Math.min(Math.max(Double.parseDouble(numStr), 0), 10);
                    parsed[slot] = true;
                    slot++;
                } catch (NumberFormatException ignored) {}
            }

            int parsedCount = 0;
            for (boolean p : parsed) if (p) parsedCount++;
            if (parsedCount < maxDocs / 2) return null; // 有效分数不足一半，放弃

            List<Integer> indices = new ArrayList<>(maxDocs);
            for (int i = 0; i < maxDocs; i++) indices.add(i);
            indices.sort((a, b) -> {
                if (parsed[a] != parsed[b]) return parsed[a] ? -1 : 1;
                return Double.compare(scores[b], scores[a]);
            });
            return indices;
        } catch (Exception e) {
            log.warn("行分数解析失败: {}", e.getMessage());
        }
        return null;
    }

    // ====== POJO ======

    /** Ollama 格式：query/documents 为纯字符串。 */
    @Data
    private static class OllamaRerankRequest {
        private String model;
        private String query;
        private List<String> documents;

        OllamaRerankRequest(String model, String query, List<String> texts) {
            this.model = model;
            this.query = query;
            this.documents = texts;
        }
    }

    /** vLLM 格式：query/documents 为 {text: ...} 对象。 */
    @Data
    private static class VllmRerankRequest {
        private String model;
        private TextObj query;
        private List<TextObj> documents;

        VllmRerankRequest(String model, String query, List<String> texts) {
            this.model = model;
            this.query = new TextObj(query);
            this.documents = texts.stream().map(TextObj::new).toList();
        }
    }

    @Data
    private static class TextObj {
        private String text;
        TextObj(String text) { this.text = text; }
    }

    /** 响应：兼容 Ollama（results）和 vLLM（data）。 */
    @Data
    private static class RerankResponse {
        @JsonProperty("data")
        @JsonAlias("results")
        private List<RerankResult> items;
        private String model;
    }

    @Data
    private static class RerankResult {
        private Integer index;
        @JsonProperty("relevance_score")
        private Double relevanceScore;
    }
}
