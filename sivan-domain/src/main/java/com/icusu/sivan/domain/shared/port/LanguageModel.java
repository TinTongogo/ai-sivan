package com.icusu.sivan.domain.shared.port;

import com.icusu.sivan.domain.forest.event.ChatEvent;
import com.icusu.sivan.domain.forest.vo.ChatResult;
import com.icusu.sivan.domain.forest.vo.ModelCapabilities;
import com.icusu.sivan.domain.forest.vo.ModelParams;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 语言模型 — v2.0 中唯一的 LLM 调用接口。
 * <p>
 * 无论底层是 OpenAI、Anthropic 还是本地模型，调用方看到的都是这个接口。
 */
public interface LanguageModel {

    /**
     * 模型标识，全局唯一。
     */
    String modelId();

    /**
     * 模型能力声明。
     */
    ModelCapabilities capabilities();

    /**
     * 流式对话接口。
     * 所有特性（stream / thinking / tool_use / vision）通过同一个 Flux 表达。
     */
    Flux<ChatEvent> chat(List<Msg> messages, ModelParams params);

    /**
     * 非流式简写：等待 Flux 完成，返回完整结果。
     */
    default Mono<ChatResult> complete(List<Msg> messages, ModelParams params) {
        return chat(messages, params)
                .ofType(ChatEvent.Completed.class)
                .singleOrEmpty()
                .map(ChatEvent.Completed::result);
    }

    /**
     * 消息 — 一次对话中的一条消息。
     */
    record Msg(String role, String content) {
        public static Msg user(String content) {
            return new Msg("user", content);
        }

        public static Msg assistant(String content) {
            return new Msg("assistant", content);
        }

        public static Msg system(String content) {
            return new Msg("system", content);
        }
    }
}
