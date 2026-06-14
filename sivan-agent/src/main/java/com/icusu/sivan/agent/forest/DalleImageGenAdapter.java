package com.icusu.sivan.agent.forest;

import com.icusu.sivan.domain.shared.port.ImageGenCapability;
import com.icusu.sivan.domain.forest.event.ImageGenEvent;
import com.icusu.sivan.domain.forest.vo.ImagePrompt;
import com.icusu.sivan.domain.forest.vo.ModelParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * OpenAI DALL-E 图像生成适配器 — 实现 ImageGenCapability。
 * <p>
 * 由 {@link ProviderFactory} 创建并注册，复用 LLM Provider 的 API 配置。
 */
public class DalleImageGenAdapter implements ImageGenCapability {

    private static final Logger log = LoggerFactory.getLogger(DalleImageGenAdapter.class);

    private final String modelId;
    private final WebClient client;
    private final String apiKey;

    public DalleImageGenAdapter(String modelId, String baseUrl, String apiKey) {
        this.modelId = modelId;
        this.client = WebClient.create(baseUrl);
        this.apiKey = apiKey;
    }

    @Override
    public String modelId() { return modelId; }

    @Override
    public Flux<ImageGenEvent> generate(ImagePrompt prompt, ModelParams params) {
        log.info("[DALL-E] 生成图像: prompt={} size={} n={}", prompt.prompt(), prompt.size(), prompt.n());

        Map<String, Object> body = Map.of(
                "model", modelId.startsWith("dall-e") ? modelId : "dall-e-3",
                "prompt", prompt.prompt(),
                "n", prompt.n(),
                "size", prompt.size() != null ? prompt.size() : "1024x1024",
                "response_format", "url"
        );

        return client.post()
                .uri("/v1/images/generations")
                .header("Authorization", "Bearer " + apiKey)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(DalleResponse.class)
                .flatMapMany(response -> {
                    if (response.data == null || response.data.isEmpty()) {
                        return Flux.just(new ImageGenEvent.Error(
                                new RuntimeException("DALL-E 返回空数据")));
                    }
                    return Flux.fromIterable(response.data.stream()
                            .map(img -> (ImageGenEvent) new ImageGenEvent.Completed(
                                    img.url, "png", 1024, 1024))
                            .toList());
                })
                .onErrorResume(e -> {
                    log.error("[DALL-E] 生成失败: {}", e.getMessage());
                    return Flux.just(new ImageGenEvent.Error(e));
                });
    }

    record DalleResponse(long created, java.util.List<DalleImage> data) {}
    record DalleImage(String url, String b64Json) {}
}
