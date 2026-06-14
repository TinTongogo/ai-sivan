package com.icusu.sivan.domain.forest.port;

import com.icusu.sivan.domain.forest.ForestEvent;
import com.icusu.sivan.domain.forest.context.ExecutionContext;
import com.icusu.sivan.domain.forest.tree.ExecutableNode;
import reactor.core.publisher.Flux;

/**
 * 递归延续回调 — 避免 ModeStrategy 直接依赖 ForestExecutor。
 * <p>
 * 函数式接口：ModeStrategy 在编排子节点时调用 {@link #execute(ExecutableNode, ExecutionContext, int)}
 * 继续执行，ForestExecutor 则在 executeNode 中传入此回调。
 */
@FunctionalInterface
public interface Continuation {

    /**
     * 继续执行子节点。
     *
     * @param child  子节点（必须是 ExecutableNode）
     * @param ctx    执行上下文
     * @param depth  递归深度
     * @return 子节点执行产生的事件流
     */
    Flux<ForestEvent> execute(ExecutableNode child, ExecutionContext ctx, int depth);
}
