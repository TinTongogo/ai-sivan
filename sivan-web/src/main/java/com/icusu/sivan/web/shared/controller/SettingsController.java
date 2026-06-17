package com.icusu.sivan.web.shared.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.icusu.sivan.agent.prompt.IntentClassifier;
import com.icusu.sivan.common.dto.BaseResponse;
import com.icusu.sivan.infra.forest.entity.IntentPrototypeEntity;
import com.icusu.sivan.infra.forest.repository.IntentPrototypeJpaRepository;
import com.icusu.sivan.domain.model.LlmProvider;
import com.icusu.sivan.application.knowledge.KnowledgeBaseService;
import com.icusu.sivan.application.model.dto.ConnectionTestResult;
import com.icusu.sivan.application.model.dto.EmbeddingConfigDTO;
import com.icusu.sivan.application.model.dto.ModelServiceTestResult;
import com.icusu.sivan.common.util.UrlValidator;
import com.icusu.sivan.application.service.LlmProviderService;
import com.icusu.sivan.web.shared.security.CurrentAccountId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
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
    private final KnowledgeBaseService knowledgeBaseService;
    private final IntentClassifier intentClassifier;
    private final IntentPrototypeJpaRepository prototypeRepo;

    @Value("${sivan.embedding.default-url:}")
    private String defaultEmbeddingUrl;
    @Value("${sivan.embedding.default-model:}")
    private String defaultEmbeddingModel;
    @Value("${sivan.reranker.default-url:}")
    private String defaultRerankerUrl;
    @Value("${sivan.reranker.default-model:}")
    private String defaultRerankerModel;

    public SettingsController(LlmProviderService llmProviderService, KnowledgeBaseService knowledgeBaseService,
                              IntentClassifier intentClassifier,
                              IntentPrototypeJpaRepository prototypeRepo) {
        this.llmProviderService = llmProviderService;
        this.knowledgeBaseService = knowledgeBaseService;
        this.intentClassifier = intentClassifier;
        this.prototypeRepo = prototypeRepo;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT);
        factory.setReadTimeout(READ_TIMEOUT);
        this.restTemplate = new RestTemplate(factory);
    }

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ========== 意图原型配置 ==========

    /**
     * 获取当前闲聊/任务原型文本。
     * 用户可自定义这些原型以优化意图分类准确性。
     */
    @GetMapping("/intent-prototypes")
    public BaseResponse<Map<String, String>> getIntentPrototypes() {
        var chatPrototype = intentClassifier.getChatPrototype();
        // 从 DB 加载最新值（取 DB 中的，而非已缓存的）
        return BaseResponse.success(Map.of(
                "chat", intentClassifier.getChatPrototype(),
                "task", intentClassifier.getTaskPrototype()
        ));
    }

    /**
     * 获取意图分类反馈日志（最近 50 条）。
     */
    @GetMapping("/intent-prototypes/feedback")
    public BaseResponse<List<com.icusu.sivan.infra.forest.entity.IntentFeedbackLogEntity>> getIntentFeedback(
            @CurrentAccountId UUID accountId) {
        return BaseResponse.success(intentClassifier.getRecentLogs(accountId, 50));
    }

    /**
     * 获取意图分类准确率统计。
     */
    @GetMapping("/intent-prototypes/accuracy")
    public BaseResponse<Map<String, Object>> getIntentAccuracy(@CurrentAccountId UUID accountId) {
        long total = intentClassifier.getTotalFeedbackCount(accountId);
        long incorrect = intentClassifier.getIncorrectCount(accountId);
        double accuracy = total > 0 ? (double) (total - incorrect) / total : 0;
        return BaseResponse.success(Map.of(
                "totalFeedback", total,
                "incorrect", incorrect,
                "accuracy", Math.round(accuracy * 10000) / 100.0
        ));
    }

    /**
     * 更新意图原型文本，更新后立即生效。
     *
     * @param prototypes 包含 chat 和 task 两个 key 的 Map
     */
    @PutMapping("/intent-prototypes")
    public BaseResponse<Void> updateIntentPrototypes(@RequestBody Map<String, String> prototypes) {
        String chat = prototypes.get("chat");
        String task = prototypes.get("task");
        if (chat == null || task == null || chat.isBlank() || task.isBlank()) {
            throw new IllegalArgumentException("chat 和 task 原型文本不能为空");
        }
        var now = java.time.OffsetDateTime.now();
        prototypeRepo.save(com.icusu.sivan.infra.forest.entity.IntentPrototypeEntity.builder()
                .prototypeKey("chat").prototypeText(chat).updatedAt(now).build());
        prototypeRepo.save(com.icusu.sivan.infra.forest.entity.IntentPrototypeEntity.builder()
                .prototypeKey("task").prototypeText(task).updatedAt(now).build());
        intentClassifier.reload();
        log.info("意图原型已更新: chat={}chars task={}chars", chat.length(), task.length());
        return BaseResponse.success();
    }

    // ========== Embedding/Reranker 配置 ==========

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
                .embeddingUrl(embedding != null ? embedding.getBaseUrl() :
                        !defaultEmbeddingUrl.isBlank() ? defaultEmbeddingUrl : null)
                .embeddingModel(embedding != null ? embedding.getPrimaryModelName() :
                        !defaultEmbeddingModel.isBlank() ? defaultEmbeddingModel : null)
                .rerankerUrl(reranker != null ? reranker.getBaseUrl() :
                        !defaultRerankerUrl.isBlank() ? defaultRerankerUrl : null)
                .rerankerModel(reranker != null ? reranker.getPrimaryModelName() :
                        !defaultRerankerModel.isBlank() ? defaultRerankerModel : null)
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
        log.info("Embedding/Reranker 配置已更新，请手动重建索引以使变更生效");
        return BaseResponse.success(config);
    }

    /**
     * 重建全部向量索引（Embedding/Reranker 配置变更后必须调用）。
     * 注意：此操作耗时较长，会重新向量化所有知识库文档、记忆条目和用户画像。
     */
    @PostMapping("/embedding-config/rebuild-index")
    public BaseResponse<Void> rebuildAllIndexes(@CurrentAccountId UUID accountId) {
        log.warn("开始全局重建向量索引");
        knowledgeBaseService.rebuildAllIndexes(accountId);
        log.info("全局重建向量索引完成");
        return BaseResponse.success();
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
