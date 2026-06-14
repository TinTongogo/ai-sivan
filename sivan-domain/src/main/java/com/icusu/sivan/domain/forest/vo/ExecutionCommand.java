package com.icusu.sivan.domain.forest.vo;

import com.icusu.sivan.domain.forest.context.ExecutionContext;
import com.icusu.sivan.domain.forest.context.Delivery;
import com.icusu.sivan.domain.forest.tree.ExecutableNode;

import java.time.Instant;
import java.util.UUID;

/**
 * 执行命令 — 封装一次 Forest 执行请求的值对象。
 * <p>
 * 由 {@link ForestScheduler} 调度执行，支持优先级比较。
 * 命令包含执行所需的所有参数，Scheduler 不直接依赖 ForestNode 类型。
 */
public class ExecutionCommand implements Comparable<ExecutionCommand> {

    private final UUID commandId;
    private final ExecutableNode root;
    private final ExecutionContext ctx;
    private final Delivery delivery;
    private final int priority;
    private final Instant submittedAt;
    private final String forestId;

    public ExecutionCommand(ExecutableNode root, ExecutionContext ctx, Delivery delivery, int priority, String forestId) {
        this.commandId = UUID.randomUUID();
        this.root = root;
        this.ctx = ctx;
        this.delivery = delivery;
        this.priority = priority;
        this.submittedAt = Instant.now();
        this.forestId = forestId;
    }

    public ExecutionCommand(ExecutableNode root, ExecutionContext ctx, Delivery delivery, String forestId) {
        this(root, ctx, delivery, 0, forestId);
    }

    public UUID commandId() { return commandId; }
    public ExecutableNode root() { return root; }
    public ExecutionContext ctx() { return ctx; }
    public Delivery delivery() { return delivery; }
    public int priority() { return priority; }
    public Instant submittedAt() { return submittedAt; }
    public String forestId() { return forestId; }

    @Override
    public int compareTo(ExecutionCommand other) {
        int p = Integer.compare(other.priority, this.priority);
        return p != 0 ? p : this.submittedAt.compareTo(other.submittedAt);
    }

    @Override
    public String toString() {
        return "ExecutionCommand{id=" + commandId.toString().substring(0, 8)
                + ", priority=" + priority
                + ", submitted=" + submittedAt + "}";
    }
}
