package com.icusu.sivan.core.filter;

import com.icusu.sivan.core.model.Model;
import com.icusu.sivan.core.message.Msg;
import reactor.core.publisher.Mono;

import java.util.List;

public abstract class ModelFilter {

    private ModelFilter next;

    /** 由 FilterChain 组装时调用 */
    public final void chain(ModelFilter next) {
        this.next = next;
    }

    public Mono<Model.ModelResponse> filter(
            Model model, List<Msg> msgs, Model.ModelParams params) {
        return doFilter(model, msgs, params);
    }

    protected abstract Mono<Model.ModelResponse> doFilter(
            Model model, List<Msg> msgs, Model.ModelParams params);

    protected Mono<Model.ModelResponse> proceed(
            Model model, List<Msg> msgs, Model.ModelParams params) {
        return next != null
                ? next.filter(model, msgs, params)
                : model.chat(msgs, params);
    }
}
