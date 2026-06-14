package com.icusu.sivan.domain.forest.port;

import com.icusu.sivan.domain.forest.ForestEvent;
import com.icusu.sivan.domain.shared.port.EventSink;
import com.icusu.sivan.domain.forest.context.ExecutionContext;
import com.icusu.sivan.domain.forest.tree.TreeNode;
import reactor.core.publisher.Flux;

/**
 * 叶子执行器接口 — 每种叶子节点类型对应一个实现。
 * <p>
 * 开闭原则：新增叶子类型 = 新增一个实现类 + 注册到 LeafExecutorRegistry。零个已有文件被修改。
 */
public interface LeafExecutor {

    /** 支持的节点类型（与 {@code forest_nodes.node_type} 对应） */
    String supportedType();

    /**
     * 执行叶子节点。
     *
     * @param node  叶子节点
     * @param ctx   执行上下文（已冻结）
     * @param sink  事件输出通道
     * @return 执行产生的事件流
     */
    Flux<ForestEvent> execute(TreeNode node, ExecutionContext ctx, EventSink sink);

    /** 可选：失败后是否重试，默认不重试 */
    default int maxRetries() {
        return 0;
    }
}
