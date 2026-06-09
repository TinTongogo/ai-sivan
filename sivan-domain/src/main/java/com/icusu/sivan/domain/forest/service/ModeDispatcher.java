package com.icusu.sivan.domain.forest.service;

import com.icusu.sivan.domain.forest.ForestEvent;
import com.icusu.sivan.domain.forest.context.ExecutionContext;
import com.icusu.sivan.domain.forest.tree.ExecutableNode;
import reactor.core.publisher.Flux;

/**
 * 编排模式分派器 — 按节点的 {@code mode} 将执行委托给对应的 {@link ModeStrategy}。
 * <p>
 * 接口定义在 domain 层，实现在 agent 层（森林核心引擎）。
 */
@FunctionalInterface
public interface ModeDispatcher {

    /**
     * 将节点分派到对应的 ModeStrategy 执行。
     *
     * @param node  当前内部节点
     * @param ctx   执行上下文
     * @param depth 递归深度
     * @param next  递归回调
     * @return 编排事件流
     */
    Flux<ForestEvent> dispatch(
            ExecutableNode node,
            ExecutionContext ctx,
            int depth,
            Continuation next
    );
}
