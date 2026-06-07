package com.icusu.sivan.agent.service;

import com.icusu.sivan.agent.model.ModelRouter;
import com.icusu.sivan.core.message.Msg;
import com.icusu.sivan.core.message.Role;
import com.icusu.sivan.core.model.Model;
import com.icusu.sivan.core.model.ModelChunk;
import com.icusu.sivan.domain.shared.vo.TokenContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
public class LlmService {

    private final ModelRouter modelRouter;
    private final Model.ModelParams defaultOptions;
    private final TokenUsageRecorder tokenUsageRecorder;

    public LlmService(ModelRouter modelRouter, TokenUsageRecorder tokenUsageRecorder) {
        this.modelRouter = modelRouter;
        this.tokenUsageRecorder = tokenUsageRecorder;
        this.defaultOptions = Model.ModelParams.defaults();
    }

    private Model getModel(UUID accountId) {
        return modelRouter.getDefaultModel(accountId);
    }

    // ========== 文本生成（响应式） ==========

    public Mono<String> chat(String systemPrompt, String userMessage, UUID accountId) {
        List<Msg> messages = buildMessages(systemPrompt, userMessage);
        return chatWithMessages(messages, accountId);
    }

    // ========== 流式生成（返回 ModelChunk） ==========

    public Flux<ModelChunk> chatStream(String systemPrompt, String userMessage, UUID accountId) {
        List<Msg> messages = buildMessages(systemPrompt, userMessage);
        return getModel(accountId).stream(messages, defaultOptions);
    }

    public Flux<ModelChunk> chatStream(String systemPrompt, String userMessage, TokenContext ctx) {
        UUID accountId = ctx.getAccountId();
        List<Msg> messages = buildMessages(systemPrompt, userMessage);
        Model model = getModel(accountId);
        return Flux.defer(() -> {
            UsageTracker tracker = new UsageTracker();
            return model.stream(messages, defaultOptions)
                    .doOnNext(tracker.captureConsumer())
                    .doFinally(signalType ->
                            tracker.saveIfNeeded(ctx, model.modelId(), tokenUsageRecorder));
        });
    }

    // ========== 内部方法 ==========

    private Mono<String> chatWithMessages(List<Msg> messages, UUID accountId) {
        return getModel(accountId).stream(messages, defaultOptions)
                .map(ModelChunk::content)
                .reduce(String::concat)
                .switchIfEmpty(Mono.just(""))
                .retryWhen(Retry.fixedDelay(1, Duration.ofSeconds(3))
                        .doBeforeRetry(rs -> log.warn("chat 失败, 重试: {}", rs.failure().toString())));
    }

    private List<Msg> buildMessages(String prompt, String userMessage) {
        List<Msg> msgs = new ArrayList<>(2);
        if (prompt != null && !prompt.isBlank()) {
            msgs.add(Msg.of(Role.SYSTEM, prompt));
        }
        if (userMessage != null && !userMessage.isBlank()) {
            msgs.add(Msg.of(Role.USER, userMessage));
        }
        return msgs;
    }
}
