package com.icusu.sivan.domain.forest.port;

import com.icusu.sivan.common.Mode;
import com.icusu.sivan.domain.forest.ForestEvent;
import com.icusu.sivan.domain.forest.context.ExecutionContext;
import com.icusu.sivan.domain.forest.tree.ExecutableNode;
import reactor.core.publisher.Flux;

/**
 * 编排策略接口 — 每种 Mode 对应一个实现。
 * <p>
 * 开闭原则：新增 Mode = 新增一个实现类 + 注册到 ModeDispatcher。零个已有文件被修改。
 */
public interface ModeStrategy {

    /** 本策略支持的 Mode */
    Mode supportedMode();

    /**
     * 执行一组 children，按本 mode 的调度规则。
     *
     * @param node   当前内部节点（含 children）
     * @param ctx    执行上下文（已冻结）
     * @param depth  当前递归深度
     * @param next   递归回调，用于继续执行子节点
     * @return 编排事件流
     */
    Flux<ForestEvent> execute(
            ExecutableNode node,
            ExecutionContext ctx,
            int depth,
            Continuation next
    );
}
