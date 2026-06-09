package com.icusu.sivan.domain.forest.context;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 执行上下文 — 贯穿一次执行请求的"不变参数"。
 * <p>
 * 架构纪律：
 * <ol>
 *   <li>只承载一次执行请求中不会变化的数据</li>
 *   <li>创建完成后调用 {@link #freeze()}，冻结后的任何修改抛 {@link IllegalStateException}</li>
 *   <li>禁止 ThreadLocal — {@code accountId} 在所有方法中显式传递</li>
 * </ol>
 */
public sealed interface ExecutionContext permits ExecutionContextImpl, FrozenContext {

    UUID accountId();

    UUID projectId();

    UUID conversationId();

    long timeoutMs();

    boolean isCancelled();

    /** 取消执行。后续 {@link #isCancelled()} 返回 {@code true}。 */
    default void cancel() {}

    ExecutionContext freeze();

    static ExecutionContext create(UUID accountId) {
        return new ExecutionContextImpl(accountId, null, null, 7_200_000, new AtomicBoolean(false));
    }

    static ExecutionContext create(UUID accountId, UUID projectId, UUID conversationId) {
        return new ExecutionContextImpl(accountId, projectId, conversationId, 7_200_000, new AtomicBoolean(false));
    }
}
