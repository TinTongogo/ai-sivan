package com.icusu.sivan.domain.forest.tree;

import com.icusu.sivan.common.Mode;
import com.icusu.sivan.common.NodeStatus;
import com.icusu.sivan.domain.forest.port.ForestVisitor;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 任务节点 — Agent + 工具调用的叶子节点。
 * <p>
 * 实现接口：{@link ExecutableNode} + {@link CompressibleNode} + {@link ContentNode}。
 */
public class TaskNode implements ExecutableNode, CompressibleNode, ContentNode {

    private final String nodeId;
    private final Mode mode;
    private final String content;
    private String nodeType;          // 默认 "task"，chat 路径设为 "message"
    private final Map<String, Object> metadata = new HashMap<>();
    private TreeNode parent;
    private NodeStatus status;
    private int order;

    // ——— CompressibleNode 字段 ———
    private double importance;
    private long estimateSubtreeTokens = -1;

    public TaskNode(String content) {
        this(UUID.randomUUID().toString(), content, NodeStatus.PENDING, "task");
    }

    public TaskNode(String nodeId, String content, NodeStatus status) {
        this(nodeId, content, status, "task");
    }

    public TaskNode(String content, String nodeType) {
        this(UUID.randomUUID().toString(), content, NodeStatus.PENDING, nodeType);
    }

    private TaskNode(String nodeId, String content, NodeStatus status, String nodeType) {
        this.nodeId = nodeId;
        this.content = content;
        this.status = status;
        this.mode = Mode.NONE;
        this.importance = 0.7;
        this.nodeType = nodeType;
    }

    // ===== TreeNode =====

    @Override
    public String nodeId() { return nodeId; }

    @Override
    public TreeNode parent() { return parent; }

    @Override
    public void setParent(TreeNode parent) { this.parent = parent; }

    @Override
    public List<TreeNode> children() { return List.of(); }

    @Override
    public boolean isLeaf() { return true; }

    @Override
    public int order() { return order; }

    @Override
    public void setOrder(int order) { this.order = order; }

    @Override
    public String nodeType() { return nodeType; }

    /** 更改节点类型（用于在运行时决定走 ChatLeafExecutor 还是 AgentLeafExecutor）。 */
    public void setNodeType(String nodeType) { this.nodeType = nodeType; }

    // ===== ExecutableNode =====

    @Override
    public Mode mode() { return mode; }

    @Override
    public NodeStatus status() { return status; }

    @Override
    public void setStatus(NodeStatus status) {
        this.status = status;
        onStatusChanged();
    }

    // ===== CompressibleNode =====

    @Override
    public double importance() { return importance; }

    public void importance(double importance) { this.importance = importance; }

    @Override
    public long estimateSelfTokens() {
        return content != null ? (long) (content.length() * 2) : 0;
    }

    @Override
    public long estimateSubtreeTokens() { return estimateSubtreeTokens; }

    public void estimateSubtreeTokens(long v) { this.estimateSubtreeTokens = v; }

    @Override
    public void invalidateTokenCache() { this.estimateSubtreeTokens = -1; }

    // ===== ContentNode =====

    @Override
    public String content() { return content; }

    @Override
    public Map<String, Object> metadata() { return metadata; }

    @Override
    public void setMetadata(Map<String, Object> metadata) {
        this.metadata.clear();
        if (metadata != null) {
            this.metadata.putAll(metadata);
        }
    }

    @Override
    public void accept(ForestVisitor v) { v.visitTask(this); }
}
