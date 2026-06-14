package com.icusu.sivan.domain.forest.tree;

import com.icusu.sivan.domain.forest.port.ForestVisitor;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 记忆节点 — 记忆条目，只读但可压缩。
 * <p>
 * 实现接口：{@link CompressibleNode} + {@link ContentNode}。
 */
public class MemoryNode implements CompressibleNode, ContentNode {

    private final String nodeId;
    private final String content;
    private final Map<String, Object> metadata = new HashMap<>();
    private TreeNode parent;
    private int order;
    private double importance;
    private long estimateSubtreeTokens = -1;

    public MemoryNode(String content, double importance) {
        this(UUID.randomUUID().toString(), content, importance);
    }

    public MemoryNode(String nodeId, String content, double importance) {
        this.nodeId = nodeId;
        this.content = content;
        this.importance = importance;
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
    public String nodeType() { return "memory"; }

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

    @Override
    public boolean isFoldable() { return importance < 0.3; }

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
    public void accept(ForestVisitor v) { v.visitMemory(this); }
}
