package com.icusu.sivan.core.context;

import com.icusu.sivan.core.message.Msg;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public interface ExecutionContext {

    String conversationId();

    ExecutionState state();

    List<Msg> messages();

    Map<String, Object> attributes();

    <T> T attribute(String key);

    ExecutionContext append(Msg msg);

    ExecutionContext withState(ExecutionState state);

    static ExecutionContext create(String conversationId) {
        return new DefaultContext(conversationId, ExecutionState.PENDING, List.of(), Map.of());
    }

    static ExecutionContext create(String conversationId, List<Msg> msgs) {
        return new DefaultContext(conversationId, ExecutionState.PENDING, List.copyOf(msgs), Map.of());
    }

    static ExecutionContext create(String conversationId, List<Msg> msgs, Map<String, Object> attributes) {
        return new DefaultContext(conversationId, ExecutionState.PENDING, List.copyOf(msgs), Map.copyOf(attributes));
    }
}

record DefaultContext(
        String conversationId,
        ExecutionState state,
        List<Msg> msgs,
        Map<String, Object> attributes
) implements ExecutionContext {

    DefaultContext {
        attributes = Map.copyOf(attributes);
    }

    @Override
    public List<Msg> messages() {
        return msgs;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T attribute(String key) {
        return (T) attributes.get(key);
    }

    @Override
    public ExecutionContext append(Msg msg) {
        var newMessages = new ArrayList<>(msgs);
        newMessages.add(msg);
        return new DefaultContext(conversationId, state, List.copyOf(newMessages), attributes);
    }

    @Override
    public ExecutionContext withState(ExecutionState state) {
        return new DefaultContext(conversationId, state, msgs, attributes);
    }
}
