package com.icusu.sivan.agent.forest;

import com.icusu.sivan.core.model.Model;
import com.icusu.sivan.core.model.ModelChunk;
import com.icusu.sivan.domain.forest.service.*;
import com.icusu.sivan.domain.model.LlmProvider;
import com.icusu.sivan.domain.model.ModelCapability;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static com.icusu.sivan.domain.forest.service.ModelCapabilities.Capability.*;

/**
 * 适配器 — 将 V1 {@link Model} 包装为 V2 {@link LanguageModel}。
 * <p>
 * 复用 V1 OpenAiModel 的 WebClient、连接池、JSON 解析等完整实现，
 * 仅做 {@code ModelChunk → ChatEvent} 的事件协议转换。
 */
public class OpenAiModelAdapter implements LanguageModel {

    private static final Logger log = LoggerFactory.getLogger(OpenAiModelAdapter.class);

    private final String modelId;
    private final ModelCapabilities capabilities;
    private final Model model;

    public OpenAiModelAdapter(Model model, LlmProvider provider) {
        this.modelId = provider.getPrimaryModelName();
        this.model = model;
        this.capabilities = buildCapabilities(provider);
    }

    @Override
    public String modelId() {
        return modelId;
    }

    @Override
    public ModelCapabilities capabilities() {
        return capabilities;
    }

    @Override
    public Flux<ChatEvent> chat(List<Msg> messages, ModelParams params) {
        var v1Messages = messages.stream()
                .map(m -> com.icusu.sivan.core.message.Msg.of(
                        com.icusu.sivan.core.message.Role.valueOf(m.role().toUpperCase()),
                        m.content()))
                .toList();

        var v1Params = new Model.ModelParams(
                params.temperature(), params.maxTokens(), null, params.extra());

        AtomicReference<String> fullText = new AtomicReference<>("");
        AtomicReference<String> fullThinking = new AtomicReference<>("");
        var finalUsage = new AtomicReference<com.icusu.sivan.core.model.TokenUsage>(null);

        long streamStart = System.nanoTime();

        return Flux.concat(
                Mono.just(new ChatEvent.Started()),

                model.stream(v1Messages, List.of(), v1Params)
                        .flatMap(chunk -> {
                            // 归约最终结果
                            if (!chunk.content().isEmpty()) {
                                fullText.updateAndGet(t -> t + chunk.content());
                            }
                            if (!chunk.thinking().isEmpty()) {
                                fullThinking.updateAndGet(t -> t + chunk.thinking());
                            }
                            if (chunk.usage() != null) {
                                finalUsage.set(chunk.usage());
                            }

                            // 转译为 ChatEvent 流
                            List<ChatEvent> events = new ArrayList<>(3);
                            if (!chunk.thinking().isEmpty()) {
                                events.add(new ChatEvent.Thinking(chunk.thinking()));
                            }
                            if (!chunk.content().isEmpty()) {
                                events.add(new ChatEvent.Chunk(chunk.content()));
                            }
                            return Flux.fromIterable(events);
                        }),

                Mono.fromCallable(() -> {
                    long elapsed = (System.nanoTime() - streamStart) / 1_000_000;
                    var usage = finalUsage.get();
                    var v2Usage = usage != null
                            ? new TokenUsage(usage.promptTokens(), usage.completionTokens(),
                            usage.thinkingTokens(), usage.totalTokens())
                            : TokenUsage.ZERO;

                    log.info("[OpenAiAdapter] 流完成: model={} text={} tokens={} elapsed={}ms",
                            modelId, fullText.get().length(), v2Usage.totalTokens(), elapsed);

                    return new ChatEvent.Completed(new ChatResult(
                            fullText.get(),
                            fullThinking.get().isEmpty() ? null : fullThinking.get(),
                            List.of(),
                            v2Usage,
                            modelId,
                            elapsed
                    ));
                })
        );
    }

    private ModelCapabilities buildCapabilities(LlmProvider provider) {
        Set<ModelCapabilities.Capability> caps = new HashSet<>();
        caps.add(STREAM); // 所有 LLM 都支持流式

        for (ModelCapability mc : provider.getCapabilitySet()) {
            switch (mc) {
                case THINKING -> caps.add(THINKING);
                case TOOL_USE -> caps.add(TOOL_USE);
                case VISION -> caps.add(VISION);
                case STREAMING -> {} // 已默认添加
                default -> {}
            }
        }

        int maxTokens = provider.getContextLength() != null ? provider.getContextLength() : 8192;

        return new ModelCapabilities(
                modelId,
                provider.getProviderType() != null ? provider.getProviderType() : "unknown",
                caps,
                maxTokens,
                0,
                maxTokens,
                List.of(),
                0, 0
        );
    }
}
