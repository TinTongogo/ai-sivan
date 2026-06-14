package com.icusu.sivan.infra.forest.model;

import com.icusu.sivan.domain.forest.event.ChatEvent;
import com.icusu.sivan.domain.forest.vo.ChatResult;
import com.icusu.sivan.domain.forest.vo.ModelCapabilities;
import com.icusu.sivan.domain.forest.vo.ModelParams;
import com.icusu.sivan.domain.forest.vo.TokenUsage;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * Echo 适配器 — 开发/回退用，返回输入内容的回显。
 * <p>
 * 不调用真实模型，直接将用户消息作为回复返回。
 */
public class EchoAdapter extends ModelProviderAdapter {

    public EchoAdapter(String modelId, ModelCapabilities capabilities) {
        super(modelId, capabilities);
    }

    @Override
    protected Flux<Object> callProvider(List<Msg> messages, ModelParams params) {
        String lastContent = messages.isEmpty() ? "" : messages.get(messages.size() - 1).content();
        return Flux.just("[Echo] " + lastContent);
    }

    @Override
    protected ChatEvent toChatEvent(Object nativeEvent) {
        String text = nativeEvent.toString();
        return new ChatEvent.Completed(new ChatResult(text, null, List.of(),
                new TokenUsage(0, estimateTokens(text), 0, estimateTokens(text)),
                modelId(), 0));
    }

    private int estimateTokens(String text) {
        return text.length() / 4;
    }
}
