package com.icusu.sivan.infra.forest.execution;

import com.icusu.sivan.domain.forest.context.Progress;
import com.icusu.sivan.domain.forest.tree.TreeNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 进度心跳 — 定期输出当前进度摘要。
 * Phase 0 简化实现，Phase 1+ 接入真实调度。
 */
@Component
public class ProgressHeartbeat {

    private static final Logger log = LoggerFactory.getLogger(ProgressHeartbeat.class);

    private final ProgressAggregator aggregator;
    private final AtomicReference<Consumer<Progress>> listener = new AtomicReference<>();

    public ProgressHeartbeat(ProgressAggregator aggregator) {
        this.aggregator = aggregator;
    }

    public void tick(TreeNode root) {
        Progress progress = aggregator.aggregate(root);
        log.info("[心跳] 进度: {}/{} 完成, {} 激活",
                progress.completed(), progress.total(), progress.activated());
        Consumer<Progress> l = listener.get();
        if (l != null) l.accept(progress);
    }

    public void setListener(Consumer<Progress> listener) {
        this.listener.set(listener);
    }
}
