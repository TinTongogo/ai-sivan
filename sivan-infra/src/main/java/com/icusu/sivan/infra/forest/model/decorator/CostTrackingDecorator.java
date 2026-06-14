package com.icusu.sivan.infra.forest.model.decorator;

import com.icusu.sivan.domain.forest.event.ChatEvent;
import com.icusu.sivan.domain.shared.port.LanguageModel;
import com.icusu.sivan.domain.forest.vo.ModelCapabilities;
import com.icusu.sivan.domain.forest.vo.ModelParams;
import com.icusu.sivan.domain.forest.vo.TokenUsage;
import com.icusu.sivan.infra.forest.model.CostTracker;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.UUID;

/**
 * 成本追踪装饰器 — 每次调用完成后记录 token 消耗和费用。
 */
public class CostTrackingDecorator implements LanguageModel {

    private final LanguageModel delegate;
    private final CostTracker costTracker;
    private final UUID accountId;
    private final UUID conversationId;

    public CostTrackingDecorator(LanguageModel delegate, CostTracker costTracker,
                                  UUID accountId, UUID conversationId) {
        this.delegate = delegate;
        this.costTracker = costTracker;
        this.accountId = accountId;
        this.conversationId = conversationId;
    }

    @Override
    public String modelId() { return delegate.modelId(); }

    @Override
    public ModelCapabilities capabilities() { return delegate.capabilities(); }

    @Override
    public Flux<ChatEvent> chat(List<LanguageModel.Msg> messages, ModelParams params) {
        return delegate.chat(messages, params)
                .doOnNext(event -> {
                    if (event instanceof ChatEvent.Completed completed) {
                        TokenUsage usage = completed.result().usage();
                        if (usage != null) {
                            costTracker.record(this, usage, accountId, conversationId);
                        }
                    }
                });
    }

    @Override
    public String toString() { return "CostTrackingDecorator(" + delegate + ")"; }
}
