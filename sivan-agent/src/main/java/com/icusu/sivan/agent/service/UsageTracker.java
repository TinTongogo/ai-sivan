package com.icusu.sivan.agent.service;

import com.icusu.sivan.core.model.Model;
import com.icusu.sivan.core.model.ModelChunk;
import com.icusu.sivan.core.model.TokenUsage;
import com.icusu.sivan.domain.shared.vo.TokenContext;

import java.util.function.Consumer;

/**
 * Token 用量追踪器，消除数组捕获模式。
 * <p>
 * 用法：
 * <pre>{@code
 * UsageTracker tracker = new UsageTracker();
 * model.stream(messages, tools, opts)
 *     .doOnNext(tracker.captureConsumer())
 *     .doFinally(signal -> tracker.saveIfNeeded(ctx, modelName, recorder));
 * }</pre>
 */
public class UsageTracker {

    private TokenUsage usage;

    /** 从 ModelChunk 中捕获用量。 */
    public void capture(ModelChunk chunk) {
        if (chunk != null && chunk.usage() != null) {
            this.usage = chunk.usage();
        }
    }

    /** 从非流式 ModelResponse 中捕获用量。 */
    public void captureFromResponse(Model.ModelResponse response) {
        if (response != null && response.usage() != null) {
            this.usage = response.usage();
        }
    }

    /** 返回 {@link #capture} 作为 Consumer，可在 doOnNext 中使用。 */
    public Consumer<ModelChunk> captureConsumer() {
        return this::capture;
    }

    /** 获取捕获的用量。 */
    public TokenUsage get() {
        return usage;
    }

    /** 总 Token 数，无用量时返回 0。 */
    public int totalTokens() {
        return usage != null ? usage.totalTokens() : 0;
    }

    /** 输入 Token 数，无用量时返回 0。 */
    public int inputTokens() {
        return usage != null ? usage.promptTokens() : 0;
    }

    /** 输出 Token 数，无用量时返回 0。 */
    public int outputTokens() {
        return usage != null ? usage.completionTokens() : 0;
    }

    /** 将捕获的用量持久化（如存在且 ctx 非空）。 */
    public void saveIfNeeded(TokenContext ctx, String modelName, TokenUsageRecorder recorder) {
        if (usage != null && ctx != null) {
            recorder.saveUsage(usage, ctx, modelName);
        }
    }

    /** 重置追踪器。 */
    public void reset() {
        this.usage = null;
    }
}
