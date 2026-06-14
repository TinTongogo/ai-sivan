package com.icusu.sivan.agent.forest;

import com.icusu.sivan.domain.forest.vo.ModelParams;
import com.icusu.sivan.domain.shared.port.SpeechSynthCapability;
import com.icusu.sivan.domain.forest.event.SpeechSynthEvent;
import com.icusu.sivan.domain.forest.vo.SpeechSynthRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.util.Map;

/**
 * OpenAI TTS 适配器 — 实现 SpeechSynthCapability。
 * <p>
 * 通过 OpenAI Audio API 将文本合成为语音。
 */
public class OpenAiTtsAdapter implements SpeechSynthCapability {

    private static final Logger log = LoggerFactory.getLogger(OpenAiTtsAdapter.class);

    private final String modelId;
    private final WebClient client;
    private final String apiKey;

    public OpenAiTtsAdapter(String modelId, String baseUrl, String apiKey) {
        this.modelId = modelId;
        this.client = WebClient.create(baseUrl);
        this.apiKey = apiKey;
    }

    @Override
    public String modelId() { return modelId; }

    @Override
    public Flux<SpeechSynthEvent> synthesize(SpeechSynthRequest request, ModelParams params) {
        log.info("[TTS] 合成语音: text={} voice={}", request.text(), request.voice());

        String voice = request.voice() != null && !request.voice().isBlank()
                ? request.voice() : "alloy";

        return client.post()
                .uri("/v1/audio/speech")
                .header("Authorization", "Bearer " + apiKey)
                .bodyValue(Map.of(
                        "model", "tts-1",
                        "input", request.text(),
                        "voice", voice,
                        "response_format", "mp3"
                ))
                .retrieve()
                .bodyToFlux(byte[].class)
                .map(bytes -> (SpeechSynthEvent) new SpeechSynthEvent.AudioChunk(bytes, "mp3"))
                .concatWithValues(new SpeechSynthEvent.Completed(0))
                .onErrorResume(e -> {
                    log.error("[TTS] 合成失败: {}", e.getMessage());
                    return Flux.just(new SpeechSynthEvent.Error(e));
                });
    }
}
