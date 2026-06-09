package com.icusu.sivan.domain.forest.context;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 冻结的执行上下文 — 任何修改操作抛 IllegalStateException。
 * <p>
 * freeze 后确保通过上下文不能修改执行参数。
 */
public record FrozenContext(
        UUID accountId,
        UUID projectId,
        UUID conversationId,
        long timeoutMs,
        AtomicBoolean cancelled
) implements ExecutionContext {

    FrozenContext(ExecutionContextImpl src) {
        this(src.accountId(), src.projectId(), src.conversationId(), src.timeoutMs(), src.cancelled());
    }

    @Override
    public boolean isCancelled() {
        return cancelled.get();
    }

    @Override
    public ExecutionContext freeze() {
        return this; // 已冻结
    }
}
