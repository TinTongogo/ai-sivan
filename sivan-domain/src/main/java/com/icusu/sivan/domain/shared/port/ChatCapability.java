package com.icusu.sivan.domain.shared.port;

import com.icusu.sivan.domain.forest.event.ChatEvent;
import com.icusu.sivan.domain.shared.port.Capability;
import com.icusu.sivan.domain.shared.port.LanguageModel;
import com.icusu.sivan.domain.forest.vo.ChatResult;
import com.icusu.sivan.domain.forest.vo.ModelParams;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 文本对话能力 — 等效于现有的 LanguageModel 接口。
 */
public interface ChatCapability extends Capability {
    Flux<ChatEvent> chat(List<LanguageModel.Msg> messages, ModelParams params);

    default Mono<ChatResult> complete(List<LanguageModel.Msg> messages, ModelParams params) {
        return chat(messages, params)
                .ofType(ChatEvent.Completed.class)
                .singleOrEmpty()
                .map(ChatEvent.Completed::result);
    }
}
