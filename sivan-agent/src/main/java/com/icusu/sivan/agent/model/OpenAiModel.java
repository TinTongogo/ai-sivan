package com.icusu.sivan.agent.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.icusu.sivan.common.util.UrlValidator;
import com.icusu.sivan.core.message.Content;
import com.icusu.sivan.core.message.Msg;
import com.icusu.sivan.core.message.Role;
import com.icusu.sivan.core.model.Model;
import com.icusu.sivan.core.model.ModelChunk;
import com.icusu.sivan.core.model.TokenUsage;
import com.icusu.sivan.core.tool.ToolSpec;
import com.icusu.sivan.domain.model.ModelCapability;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.netty.channel.ChannelOption;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link Model} 原生实现 — Spring WebClient 直接调用 OpenAI 兼容 API。
 * <p>
 */
public class OpenAiModel implements Model {

    private static final Logger log = LoggerFactory.getLogger(OpenAiModel.class);

    private final String modelName;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final Duration timeout;
    private final Set<ModelCapability> capabilities;

    public OpenAiModel(String baseUrl, String apiKey, String modelName) {
        this(baseUrl, apiKey, modelName, Duration.ofSeconds(120), EnumSet.noneOf(ModelCapability.class));
    }

    public OpenAiModel(String baseUrl, String apiKey, String modelName, Duration timeout) {
        this(baseUrl, apiKey, modelName, timeout, EnumSet.noneOf(ModelCapability.class));
    }

    public OpenAiModel(String baseUrl, String apiKey, String modelName, Duration timeout, Set<ModelCapability> capabilities) {
        this.modelName = modelName;
        this.timeout = timeout;
        this.capabilities = capabilities != null ? capabilities : EnumSet.noneOf(ModelCapability.class);
        this.objectMapper = new ObjectMapper();

        // URL 基本格式校验（协议白名单、字符安全、无内嵌凭证）
        // 严苛 SSRF 校验（私有地址限制）已在 LlmProviderService API 层完成，
        // 运行时仅做基本格式校验以兼容公网模型端点（如 api.openai.com）
        String safeUrl = baseUrl;
        if (baseUrl != null && !baseUrl.isBlank()) {
            var check = UrlValidator.validate(baseUrl);
            if (!check.valid()) {
                throw new IllegalArgumentException("Model URL 校验失败: " + check.errorMessage());
            }
            safeUrl = check.sanitizedUrl();
        }

        // 流式优化：连接池 + TCP_NODELAY + 增大读写缓冲
        ConnectionProvider connProvider = ConnectionProvider.builder(modelName)
                .maxConnections(20).pendingAcquireMaxCount(50).build();
        HttpClient httpClient = HttpClient.create(connProvider)
                .responseTimeout(timeout)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
                .option(ChannelOption.TCP_NODELAY, true);
        this.webClient = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .baseUrl(normalizeV1Url(safeUrl))
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    /**
     * 规范化 baseUrl，确保始终以 /v1/ 结尾（OpenAI 兼容 API 约定）。
     * <p>逻辑：</p>
     * <ul>
     *   <li>如果 URL path 以 {@code /v1} 或 {@code /v1/} 结尾 → 原样使用，仅确保尾随 {@code /}</li>
     *   <li>否则 → 自动追加 {@code /v1/}</li>
     * </ul>
     * 注意不会产生 {@code /v1/v1} 双写。
     */
    public static String normalizeV1Url(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) return baseUrl;
        String url = baseUrl.strip();
        // 手动去除末尾斜杠（替代 replaceAll 避免 ReDoS）
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        // 已含 /v1 路径
        if (url.endsWith("/v1")) {
            return url + "/";
        }
        if (url.contains("/v1/")) {
            return url.endsWith("/") ? url : url + "/";
        }
        // 不含 /v1，追加
        return url + "/v1/";
    }

    @Override
    public String modelId() {
        return modelName;
    }

    @Override
    public Mono<ModelResponse> chat(List<Msg> messages, List<ToolSpec> tools, ModelParams params) {
        ObjectNode body = buildChatRequest(messages, tools, params, false);

        return webClient.post()
                .uri("chat/completions")
                .bodyValue(body)
                .retrieve()
                .onStatus(s -> s.isError(), resp -> resp.bodyToMono(String.class)
                        .flatMap(errBody -> Mono.error(new RuntimeException(
                                resp.statusCode() + " from " + modelName + ": " + errBody))))
                .bodyToMono(String.class)
                .timeout(timeout)
                .map(this::parseChatResponse);
    }

    @Override
    public Flux<ModelChunk> stream(List<Msg> messages, List<ToolSpec> tools, ModelParams params) {
        long t0 = System.nanoTime();
        ObjectNode body = buildChatRequest(messages, tools, params, true);
        long t1 = System.nanoTime();
        log.warn("[Perf] modelStream: jsonBuild={}μs, model={}", (t1 - t0) / 1000, modelName);

        AtomicBoolean firstChunkLogged = new AtomicBoolean(false);

        return webClient.post()
                .uri("chat/completions")
                .bodyValue(body)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .retrieve()
                .onStatus(HttpStatusCode::isError, resp -> resp.bodyToMono(String.class)
                        .flatMap(errBody -> Mono.error(new RuntimeException(
                                resp.statusCode() + " from " + modelName + ": " + errBody))))
                .bodyToFlux(String.class)
                .retryWhen(Retry.backoff(2, Duration.ofSeconds(1))
                        .filter(e -> e instanceof org.springframework.web.reactive.function.client.WebClientRequestException)
                        .onRetryExhaustedThrow((spec, sig) -> sig.failure()))
                .timeout(timeout)
                .doOnNext(raw -> {
                    if (firstChunkLogged.compareAndSet(false, true)) {
                        log.warn("[Perf] modelStream: firstChunk={}ms, model={}", (System.nanoTime() - t0) / 1_000_000, modelName);
                    }
                })
//                .doOnNext(line -> log.debug("SSE raw: {}", line))
                .map(line -> line.startsWith("data: ") ? line.substring(6) : line)
                .filter(data -> !"[DONE]".equals(data.trim()))
//                .doOnNext(data -> log.debug("SSE chunk: {}", data))
                .map(this::parseStreamChunk);
    }

    @Override
    public Mono<List<Float>> embed(String text) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", modelName);
        body.put("input", text);

        return webClient.post()
                .uri("embeddings")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(timeout)
                .map(this::parseEmbedding);
    }

    // ====== 请求构建 ======

    private ObjectNode buildChatRequest(List<Msg> messages, List<ToolSpec> tools,
                                         ModelParams params, boolean stream) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", modelName);
        root.set("messages", toJsonMessages(messages));
        root.put("stream", stream);
        if (params.temperature() != null) root.put("temperature", params.temperature());
        if (params.maxTokens() != null) root.put("max_tokens", params.maxTokens());
        if (tools != null && !tools.isEmpty()) {
            root.set("tools", toJsonTools(tools));
        }
        params.extra().forEach((k, v) -> {
            if (v != null) root.putPOJO(k, v);
        });
        return root;
    }

    private ArrayNode toJsonMessages(List<Msg> messages) {
        ArrayNode arr = objectMapper.createArrayNode();
        for (Msg msg : messages) {
            arr.add(toJsonMessage(msg));
        }
        return arr;
    }

    private ObjectNode toJsonMessage(Msg msg) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("role", switch (msg.role()) {
            case SYSTEM -> "system";
            case USER -> "user";
            case ASSISTANT -> "assistant";
            case TOOL -> "tool";
        });

        List<Content> contents = msg.contents();
        StringBuilder textBuilder = new StringBuilder();
        StringBuilder thinkingBuilder = new StringBuilder();
        List<Content.ToolCall> toolCalls = new ArrayList<>();
        List<Content.ToolResult> toolResults = new ArrayList<>();

        for (Content c : contents) {
            switch (c) {
                case Content.Text t -> textBuilder.append(t.text());
                case Content.Thinking t -> thinkingBuilder.append(t.thinking());
                case Content.ToolCall tc -> toolCalls.add(tc);
                case Content.ToolResult tr -> {
                    toolResults.add(tr);
                    if (tr.content() != null) textBuilder.append(tr.content());
                }
                default -> {}
            }
        }

        String text = sanitizeForJson(textBuilder.toString());
        List<Content.Image> images = contents.stream()
                .filter(c -> c instanceof Content.Image)
                .map(c -> (Content.Image) c)
                .toList();
        List<Content.Audio> audios = contents.stream()
                .filter(c -> c instanceof Content.Audio)
                .map(c -> (Content.Audio) c)
                .toList();

        boolean supportsVision = capabilities.contains(ModelCapability.VISION);
        boolean supportsAudio = capabilities.contains(ModelCapability.AUDIO);
        if ((!images.isEmpty() && supportsVision) || (!audios.isEmpty() && supportsAudio)) {
            ArrayNode contentArray = objectMapper.createArrayNode();
            if (!text.isEmpty()) {
                ObjectNode textPart = contentArray.addObject();
                textPart.put("type", "text");
                textPart.put("text", text);
            }
            if (supportsVision) {
                for (Content.Image img : images) {
                    ObjectNode imgPart = contentArray.addObject();
                    imgPart.put("type", "image_url");
                    ObjectNode urlObj = imgPart.putObject("image_url");
                    urlObj.put("url", "data:" + img.mimeType() + ";base64,"
                            + Base64.getEncoder().encodeToString(img.data()));
                }
            }
            if (supportsAudio) {
                for (Content.Audio audio : audios) {
                    ObjectNode audioPart = contentArray.addObject();
                    audioPart.put("type", "input_audio");
                    ObjectNode audioObj = audioPart.putObject("input_audio");
                    audioObj.put("data", Base64.getEncoder().encodeToString(audio.data()));
                    audioObj.put("format", audio.mimeType().replace("audio/", ""));
                }
            }
            node.set("content", contentArray);
        } else if (msg.role() == Role.ASSISTANT && text.isEmpty() && !toolCalls.isEmpty()) {
            node.put("content", (String) null);
        } else {
            node.put("content", text);
        }

        String thinkingText = thinkingBuilder.toString();
        if (!thinkingText.isEmpty() && msg.role() == Role.ASSISTANT) {
            node.put("reasoning_content", thinkingText);
        }

        if (!toolCalls.isEmpty()) {
            node.set("tool_calls", toJsonToolCalls(toolCalls));
        }
        if (!toolResults.isEmpty()) {
            node.put("tool_call_id", toolResults.get(0).id());
        }

        return node;
    }

    private ArrayNode toJsonToolCalls(List<Content.ToolCall> toolCalls) {
        ArrayNode arr = objectMapper.createArrayNode();
        for (Content.ToolCall tc : toolCalls) {
            ObjectNode func = objectMapper.createObjectNode();
            func.put("name", tc.name());
            try {
                func.put("arguments", objectMapper.writeValueAsString(tc.args()));
            } catch (JsonProcessingException e) {
                func.put("arguments", "{}");
            }
            ObjectNode node = objectMapper.createObjectNode();
            node.put("id", tc.id());
            node.put("type", "function");
            node.set("function", func);
            arr.add(node);
        }
        return arr;
    }

    /** 清理可能破坏 JSON 序列化的字符（工具输出中的二进制残片等）。 */
    static String sanitizeForJson(String text) {
        if (text == null || text.isEmpty()) return text;
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\t' || c == '\n' || c == '\r') {
                sb.append(c);
            } else if (c < 0x20 || c == 0x7F) {
                sb.append(' '); // 替换控制字符为空格
            } else if (c == '\\' && i + 1 < text.length()) {
                // 检查反斜杠后是否跟非法转义（非 JSON 标准转义字符）
                char next = text.charAt(i + 1);
                if ("\"/\\bfnrtu".indexOf(next) == -1) {
                    sb.append(' '); // 非法转义（如 \x）替换为空格
                } else {
                    sb.append(c); // 合法转义，保留
                }
            } else if (c == '\\') {
                sb.append(' '); // 行尾孤立反斜杠，替换为空格
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private ArrayNode toJsonTools(List<ToolSpec> tools) {
        ArrayNode arr = objectMapper.createArrayNode();
        for (ToolSpec spec : tools) {
            ObjectNode func = objectMapper.createObjectNode();
            func.put("name", spec.name());
            if (spec.description() != null) func.put("description", spec.description());
            func.set("parameters", objectMapper.valueToTree(spec.inputSchema()));

            ObjectNode tool = objectMapper.createObjectNode();
            tool.put("type", "function");
            tool.set("function", func);
            arr.add(tool);
        }
        return arr;
    }

    // ====== 响应解析 ======

    private ModelResponse parseChatResponse(String raw) {
        try {
            JsonNode root = objectMapper.readTree(raw);
            JsonNode choices = root.get("choices");
            Msg message = Msg.of(Role.ASSISTANT, List.of());
            TokenUsage usage = TokenUsage.EMPTY;

            if (choices != null && choices.isArray() && choices.size() > 0) {
                JsonNode msgNode = choices.get(0).get("message");
                if (msgNode != null) {
                    message = parseAssistantMessage(msgNode);
                }
            }
            JsonNode usageNode = root.get("usage");
            if (usageNode != null) {
                int thinkingTk = 0;
                JsonNode details = usageNode.get("completion_tokens_details");
                if (details != null && !details.isNull()) {
                    thinkingTk = intVal(details, "reasoning_tokens");
                }
                usage = new TokenUsage(
                        intVal(usageNode, "prompt_tokens"),
                        intVal(usageNode, "completion_tokens"),
                        intVal(usageNode, "total_tokens"),
                        thinkingTk);
            }
            return new ModelResponse(message, usage);
        } catch (Exception e) {
            throw new RuntimeException("解析 Chat 响应失败", e);
        }
    }

    private Msg parseAssistantMessage(JsonNode msgNode) {
        List<Content> contents = new ArrayList<>();
        JsonNode reasoningNode = msgNode.get("reasoning_content");
        if (reasoningNode != null && !reasoningNode.isNull()) {
            contents.add(new Content.Thinking(reasoningNode.asText(), ""));
        }
        JsonNode contentNode = msgNode.get("content");
        if (contentNode != null && !contentNode.isNull()) {
            contents.add(new Content.Text(contentNode.asText()));
        }
        JsonNode tcs = msgNode.get("tool_calls");
        if (tcs != null && tcs.isArray()) {
            for (JsonNode tc : tcs) {
                String id = tc.get("id").asText();
                JsonNode func = tc.get("function");
                String name = func.get("name").asText();
                Map<String, Object> args = parseArgs(func.get("arguments"));
                contents.add(new Content.ToolCall(id, name, args));
            }
        }
        return Msg.of(Role.ASSISTANT, contents);
    }

    private Map<String, Object> parseArgs(JsonNode argsNode) {
        if (argsNode == null || argsNode.isNull()) return Map.of();
        try {
            String text = argsNode.asText();
            if (text.isBlank()) return Map.of();
            return objectMapper.readValue(text, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    private ModelChunk parseStreamChunk(String data) {
        try {
            JsonNode root = objectMapper.readTree(data);
            JsonNode choices = root.get("choices");
            // 流式响应中部分 provider 在最后一个 chunk 的根级携带 usage
            TokenUsage usage = null;
            JsonNode usageNode = root.get("usage");
            if (usageNode != null && !usageNode.isNull()) {
                int pt = intVal(usageNode, "prompt_tokens");
                int ct = intVal(usageNode, "completion_tokens");
                int tt = intVal(usageNode, "total_tokens");
                if (tt > 0) {
                    int thinkingTk = 0;
                    JsonNode details = usageNode.get("completion_tokens_details");
                    if (details != null && !details.isNull()) {
                        thinkingTk = intVal(details, "reasoning_tokens");
                    }
                    usage = new TokenUsage(pt, ct, tt, thinkingTk);
                }
            }
            if (choices != null && choices.isArray() && choices.size() > 0) {
                JsonNode delta = choices.get(0).get("delta");
                if (delta != null) {
                    String content = delta.has("content") && !delta.get("content").isNull()
                            ? delta.get("content").asText() : "";
                    String thinking = delta.has("reasoning_content") && !delta.get("reasoning_content").isNull()
                            ? delta.get("reasoning_content").asText() : "";
                    String finish = choices.get(0).has("finish_reason")
                            && !choices.get(0).get("finish_reason").isNull()
                            ? choices.get(0).get("finish_reason").asText() : null;
                    List<ModelChunk.ToolCallDelta> tcs = parseToolCallDeltas(delta);
                    return new ModelChunk(content, thinking, finish, usage, tcs);
                }
            }
            return new ModelChunk("", null, usage);
        } catch (Exception e) {
            return new ModelChunk("", "error", null);
        }
    }

    private List<ModelChunk.ToolCallDelta> parseToolCallDeltas(JsonNode delta) {
        JsonNode tcs = delta.get("tool_calls");
        if (tcs == null || !tcs.isArray() || tcs.size() == 0) return List.of();
        List<ModelChunk.ToolCallDelta> result = new ArrayList<>();
        for (JsonNode tc : tcs) {
            int idx = tc.has("index") ? tc.get("index").asInt() : 0;
            String id = tc.has("id") && !tc.get("id").isNull() ? tc.get("id").asText() : null;
            JsonNode func = tc.get("function");
            String name = null, args = null;
            if (func != null) {
                name = func.has("name") && !func.get("name").isNull() ? func.get("name").asText() : null;
                args = func.has("arguments") && !func.get("arguments").isNull() ? func.get("arguments").asText() : null;
            }
            result.add(new ModelChunk.ToolCallDelta(idx, id, name, args));
        }
        return result;
    }

    private List<Float> parseEmbedding(String raw) {
        try {
            JsonNode root = objectMapper.readTree(raw);
            JsonNode data = root.get("data");
            if (data != null && data.isArray() && data.size() > 0) {
                JsonNode values = data.get(0).get("embedding");
                if (values != null && values.isArray()) {
                    List<Float> result = new ArrayList<>();
                    for (JsonNode v : values) result.add((float) v.asDouble());
                    return result;
                }
            }
            return List.of();
        } catch (Exception e) {
            throw new RuntimeException("解析 Embedding 响应失败", e);
        }
    }

    private static int intVal(JsonNode node, String field) {
        JsonNode f = node.get(field);
        return f != null ? f.asInt(0) : 0;
    }
}
