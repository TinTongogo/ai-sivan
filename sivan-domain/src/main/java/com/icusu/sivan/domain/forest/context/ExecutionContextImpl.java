package com.icusu.sivan.domain.forest.context;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 可变的执行上下文实现 — freeze() 后转为 FrozenContext。
 */
public record ExecutionContextImpl(
        UUID accountId,
        UUID projectId,
        UUID conversationId,
        long timeoutMs,
        AtomicBoolean cancelled
) implements ExecutionContext {

    @Override
    public boolean isCancelled() {
        return cancelled.get();
    }

    @Override
    public void cancel() {
        cancelled.set(true);
    }

    @Override
    public ExecutionContext freeze() {
        return new FrozenContext(this);
    }
}
