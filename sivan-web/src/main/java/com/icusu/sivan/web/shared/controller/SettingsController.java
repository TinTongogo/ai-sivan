package com.icusu.sivan.web.shared.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.icusu.sivan.common.dto.BaseResponse;
import com.icusu.sivan.domain.model.LlmProvider;
import com.icusu.sivan.web.model.dto.ConnectionTestResult;
import com.icusu.sivan.web.model.dto.EmbeddingConfigDTO;
import com.icusu.sivan.web.model.dto.ModelServiceTestResult;
import com.icusu.sivan.common.util.UrlValidator;
import com.icusu.sivan.web.service.LlmProviderService;
import com.icusu.sivan.web.shared.security.CurrentAccountId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.Map;
import java.util.UUID;

/**
 * 系统设置控制器（Embedding/Reranker 配置与测试）。
 */
@Slf4j
@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private final LlmProviderService llmProviderService;
    private static final int CONNECT_TIMEOUT = 5000;
    private static final int READ_TIMEOUT = 15000;
    private final RestTemplate restTemplate;

    public SettingsController(LlmProviderService llmProviderService) {
        this.llmProviderService = llmProviderService;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT);
        factory.setReadTimeout(READ_TIMEOUT);
        this.restTemplate = new RestTemplate(factory);
    }

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 获取模型运行时配置（从 llm_providers 表读取）。
     * Embedding 和 Reranker 为可选配置，未配置时对应字段返回 null。
     */
    @GetMapping("/embedding-config")
    public BaseResponse<EmbeddingConfigDTO> getEmbeddingConfig(@CurrentAccountId UUID accountId) {
        LlmProvider embedding = llmProviderService.findByTag("embedding")
                .filter(p -> p.getBaseUrl() != null && !p.getBaseUrl().isBlank())
                .orElse(null);
        LlmProvider reranker = llmProviderService.findByTag("reranker")
                .filter(p -> p.getBaseUrl() != null && !p.getBaseUrl().isBlank())
                .orElse(null);
        return BaseResponse.success(EmbeddingConfigDTO.builder()
                .embeddingUrl(embedding != null ? embedding.getBaseUrl() : null)
                .embeddingModel(embedding != null ? embedding.getPrimaryModelName() : null)
                .rerankerUrl(reranker != null ? reranker.getBaseUrl() : null)
                .rerankerModel(reranker != null ? reranker.getPrimaryModelName() : null)
                .build());
    }

    /**
     * 更新模型运行时配置（持久化到 llm_providers 表）。
     */
    @PutMapping("/embedding-config")
    public BaseResponse<EmbeddingConfigDTO> updateEmbeddingConfig(@RequestBody EmbeddingConfigDTO config,
                                                                  @CurrentAccountId UUID accountId) {
        // SSRF 防护：URL 入库前校验（DNS 解析 + 私有地址白名单）
        if (config.getEmbeddingUrl() != null && !config.getEmbeddingUrl().isBlank()) {
            var check = UrlValidator.validatePrivateAccess(config.getEmbeddingUrl());
            if (!check.valid()) throw new IllegalArgumentException("Embedding URL 无效: " + check.errorMessage());
        }
        if (config.getRerankerUrl() != null && !config.getRerankerUrl().isBlank()) {
            var check = UrlValidator.validatePrivateAccess(config.getRerankerUrl());
            if (!check.valid()) throw new IllegalArgumentException("Reranker URL 无效: " + check.errorMessage());
        }
        llmProviderService.upsertSystemProvider("embedding", config.getEmbeddingUrl(), config.getEmbeddingModel());
        llmProviderService.upsertSystemProvider("reranker", config.getRerankerUrl(), config.getRerankerModel());
        log.info("模型配置已更新: embeddingUrl={}, rerankerUrl={}",
                config.getEmbeddingUrl(), config.getRerankerUrl());
        return BaseResponse.success(config);
    }

    /**
     * 测试 Embedding 服务连通性。URL 自动补全 /api/embed（用户可配完整路径或 Ollama 基地址）。
     */
    @PostMapping("/embedding/test")
    public BaseResponse<ConnectionTestResult> testEmbedding(@RequestBody EmbeddingConfigDTO config,
                                                            @CurrentAccountId UUID accountId) {
        String embedUrl = config.getEmbeddingUrl();
        // SSRF 防护：DNS 解析 + 私有地址白名单校验
        var urlCheck = UrlValidator.validatePrivateAccess(embedUrl);
        if (!urlCheck.valid()) {
            return BaseResponse.success(ConnectionTestResult.builder().success(false).message(urlCheck.errorMessage()).build());
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        try {
            String body = buildEmbeddingBody(config.getEmbeddingModel());
            String requestUrl = normalizeEmbedUrl(urlCheck.sanitizedUrl());
            // 最终校验后构造 URI——SAST 工具通过 URI 类型识别 URL 已消毒
            var finalCheck = UrlValidator.validate(requestUrl);
            if (!finalCheck.valid()) {
                return BaseResponse.success(ConnectionTestResult.builder().success(false).message("URL 校验失败: " + finalCheck.errorMessage()).build());
            }
            URI callUri = URI.create(finalCheck.sanitizedUrl());
            restTemplate.exchange(callUri, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
            return BaseResponse.success(ConnectionTestResult.builder().success(true).message("连接成功").build());
        } catch (Exception e) {
            String msg = sanitizedError(e);
            // 405 + 未含路径 → 提示用户正确的 URL 格式
            if (e instanceof RestClientResponseException rcre && rcre.getStatusCode().value() == 405
                    && !urlCheck.sanitizedUrl().contains("/v1/")) {
                msg += "（提示：Ollama Embedding 需完整路径，如 http://localhost:11434/v1/embeddings）";
            }
            return BaseResponse.success(ConnectionTestResult.builder().success(false).message("连接失败: " + msg).build());
        }
    }

    /**
     * 测试 Reranker 服务连通性。URL 自动补全 /api/rerank。
     */
    @PostMapping("/reranker/test")
    public BaseResponse<ConnectionTestResult> testReranker(@RequestBody EmbeddingConfigDTO config,
                                                           @CurrentAccountId UUID accountId) {
        String rerankUrl = config.getRerankerUrl();
        // SSRF 防护：DNS 解析 + 私有地址白名单校验
        var urlCheck = UrlValidator.validatePrivateAccess(rerankUrl);
        if (!urlCheck.valid()) {
            return BaseResponse.success(ConnectionTestResult.builder().success(false).message(urlCheck.errorMessage()).build());
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        try {
            String body = buildRerankerBody(config.getRerankerModel());
            String requestUrl = normalizeRerankUrl(urlCheck.sanitizedUrl());
            // 最终校验后构造 URI——SAST 工具通过 URI 类型识别 URL 已消毒
            var finalCheck = UrlValidator.validate(requestUrl);
            if (!finalCheck.valid()) {
                return BaseResponse.success(ConnectionTestResult.builder().success(false).message("URL 校验失败: " + finalCheck.errorMessage()).build());
            }
            URI callUri = URI.create(finalCheck.sanitizedUrl());
            restTemplate.exchange(callUri, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
            return BaseResponse.success(ConnectionTestResult.builder().success(true).message("连接成功").build());
        } catch (Exception e) {
            return BaseResponse.success(ConnectionTestResult.builder().success(false).message("连接失败: " + sanitizedError(e)).build());
        }
    }

    /** URL 规范化：不含 API 路径时自动追加 /v1/embeddings（OpenAI 兼容，Ollama 也支持）。 */
    private static String normalizeEmbedUrl(String url) {
        if (url == null || url.isBlank()) return url;
        url = url.strip();
        url = trimTrailingSlashes(url);
        var urlCheck = UrlValidator.validate(url);
        if (!urlCheck.valid()) {
            throw new IllegalArgumentException("URL 校验失败: " + urlCheck.errorMessage());
        }
        if (url.contains("/api/embed") || url.contains("/v1/embeddings")) {
            return url;
        }
        if (url.endsWith("/v1") || url.endsWith("/v1/")) {
            return trimTrailingSlashes(url) + "/embeddings";
        }
        return url + "/v1/embeddings";
    }

    /** URL 规范化：不含 API 路径时自动追加 /api/rerank（Ollama），含 /v1/ 则追加 /v1/score（vLLM）。 */
    private static String normalizeRerankUrl(String url) {
        if (url == null || url.isBlank()) return url;
        url = url.strip();
        url = trimTrailingSlashes(url);
        var urlCheck = UrlValidator.validate(url);
        if (!urlCheck.valid()) {
            throw new IllegalArgumentException("URL 校验失败: " + urlCheck.errorMessage());
        }
        if (url.contains("/v1/") || url.endsWith("/rerank") || url.endsWith("/score")) {
            return url;
        }
        return url + "/api/rerank";
    }

    /**
     * 从异常中提取有意义的错误消息，优先解析 Ollama JSON 响应体。
     * 兼容两种 error 格式：
     *   - Map 格式：{"error": {"message": "..."}}（部分代理/网关）
     *   - String 格式：{"error": "model xxx not found"}（Ollama 原生）
     */
    private String extractErrorMessage(Exception e) {
        if (e instanceof RestClientResponseException rcre) {
            try {
                String body = rcre.getResponseBodyAsString();
                if (body != null && !body.isBlank()) {
                    Map<?, ?> parsed = objectMapper.readValue(body, Map.class);
                    Object error = parsed.get("error");
                    if (error instanceof Map<?, ?> errMap) {
                        Object msg = errMap.get("message");
                        if (msg != null) return msg.toString();
                    }
                    if (error instanceof String errStr) {
                        return errStr;
                    }
                }
            } catch (Exception ignored) { }
            return rcre.getStatusCode().value() + " " + rcre.getStatusText();
        }
        return e.getMessage();
    }

    /**
     * 测试 Embedding / Reranker 服务连通性（合并）。
     */
    @PostMapping("/embedding-config/test")
    public BaseResponse<ModelServiceTestResult> testConnection(@RequestBody EmbeddingConfigDTO config,
                                                               @CurrentAccountId UUID accountId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        // 测试 Embedding（URL 自动补全路径）
        boolean embeddingOk = false;
        String embeddingMsg;
        String embedUrl = config.getEmbeddingUrl();
        var embedUrlCheck = UrlValidator.validatePrivateAccess(embedUrl);
        if (!embedUrlCheck.valid()) {
            embeddingMsg = "URL 校验失败: " + embedUrlCheck.errorMessage();
        } else {
            String safeUrl = embedUrlCheck.sanitizedUrl();
            try {
                String body = buildEmbeddingBody(config.getEmbeddingModel());
                URI callUri = URI.create(normalizeEmbedUrl(safeUrl));
                restTemplate.exchange(callUri, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
                embeddingOk = true;
                embeddingMsg = "连接成功";
            } catch (Exception e) {
                embeddingMsg = "连接失败: " + sanitizedError(e);
            }
        }

        // 测试 Reranker（URL 自动补全路径）
        boolean rerankerOk = false;
        String rerankerMsg;
        String rerankUrl = config.getRerankerUrl();
        var rerankUrlCheck = UrlValidator.validatePrivateAccess(rerankUrl);
        if (!rerankUrlCheck.valid()) {
            rerankerMsg = "URL 校验失败: " + rerankUrlCheck.errorMessage();
        } else {
            String safeUrl = rerankUrlCheck.sanitizedUrl();
            try {
                String body = buildRerankerBody(config.getRerankerModel());
                URI callUri = URI.create(normalizeRerankUrl(safeUrl));
                restTemplate.exchange(callUri, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
                rerankerOk = true;
                rerankerMsg = "连接成功";
            } catch (Exception e) {
                rerankerMsg = "连接失败: " + sanitizedError(e);
            }
        }

        return BaseResponse.success(ModelServiceTestResult.builder()
                .embeddingSuccess(embeddingOk)
                .embeddingMessage(embeddingMsg)
                .rerankerSuccess(rerankerOk)
                .rerankerMessage(rerankerMsg)
                .build());
    }

    // ========== 安全辅助方法 ==========

    /** 去除末尾斜杠（避免 ReDoS，替代 replaceAll("/+$", "")）。 */
    private static String trimTrailingSlashes(String url) {
        if (url == null || url.isEmpty()) return url;
        int end = url.length();
        while (end > 0 && url.charAt(end - 1) == '/') end--;
        return end < url.length() ? url.substring(0, end) : url;
    }

    /**
     * 用 ObjectMapper 构建 Embedding 请求体，防止 JSON 注入。
     */
    private String buildEmbeddingBody(String model) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "model", model != null ? model : "",
                    "input", new String[]{"test"}
            ));
        } catch (JsonProcessingException e) {
            log.warn("构建 Embedding 请求体失败", e);
            return "{\"model\":\"\",\"input\":[\"test\"]}";
        }
    }

    /**
     * 用 ObjectMapper 构建 Reranker 请求体，防止 JSON 注入。
     */
    private String buildRerankerBody(String model) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "model", model != null ? model : "",
                    "query", "test",
                    "documents", new String[]{"test"}
            ));
        } catch (JsonProcessingException e) {
            log.warn("构建 Reranker 请求体失败", e);
            return "{\"model\":\"\",\"query\":\"test\",\"documents\":[\"test\"]}";
        }
    }

    /**
     * 异常信息脱敏 — 防止内部细节泄露到前端，同时保留关键状态码。
     */
    private String sanitizedError(Exception e) {
        // 将 e.getMessage() 记入日志供排查
        log.debug("连通性测试异常详情: {}", e.getMessage());
        if (e instanceof RestClientResponseException rcre) {
            String msg = extractErrorMessage(e);
            // 如果消息包含路径、堆栈等模式，截取前 120 字符
            return msg.length() > 120 ? msg.substring(0, 120) + "..." : msg;
        }
        if (e.getMessage() != null && e.getMessage().length() > 120) {
            return e.getMessage().substring(0, 120) + "...";
        }
        // 对于连接超时等常见异常，返回友好提示
        if (e instanceof java.net.ConnectException) {
            return "连接被拒绝，请确认服务已启动";
        }
        if (e instanceof java.net.SocketTimeoutException) {
            return "连接超时，请检查网络或服务状态";
        }
        if (e instanceof java.net.UnknownHostException) {
            return "无法解析主机地址，请检查 URL";
        }
        String msg = e.getMessage();
        return msg != null ? msg : "未知错误";
    }
}
